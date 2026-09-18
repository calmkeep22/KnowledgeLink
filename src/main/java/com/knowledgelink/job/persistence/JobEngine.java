package com.knowledgelink.job.persistence;

import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.application.LeaseLostException;
import com.knowledgelink.job.application.NewJob;
import com.knowledgelink.job.application.QueueSnapshot;
import com.knowledgelink.job.application.RetryPolicy;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 큐와 실행 슬롯(명세 03 5장). 선점·lease 갱신·완료·재시도·복구를 각각 한 transaction에서 원자적으로 처리한다.
 *
 * <p>JPA 대신 SQL을 직접 쓴다. 선점에는 {@code FOR UPDATE SKIP LOCKED}가, 소유권 검사에는
 * "작업과 슬롯 양쪽의 run_token이 같고 lease가 아직 유효할 때만"이라는 조건이 필요하기 때문이다.
 * 잠금 순서는 명세대로 execution_slot → job이다. 시각은 DB의 now()가 아니라 주입한 {@link Clock}에서 읽는다.
 */
@Service
public class JobEngine {

    /** lease가 만료되어 복구된 작업의 오류 코드. */
    public static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    /** 예산·건수는 작업이 처음 들어온 달에 귀속하며 기준 시간대는 서울이다(명세 05 7장). */
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final JobProperties properties;
    private final RetryPolicy retryPolicy;

    public JobEngine(NamedParameterJdbcTemplate jdbc, Clock clock, JobProperties properties, RetryPolicy retryPolicy) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
    }

    /** QUEUED로 넣는다. 같은 scope에 활성 SYNC가 이미 있으면 새로 만들지 않고 그 작업 ID를 돌려준다. */
    @Transactional
    public UUID enqueue(NewJob job) {
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UuidV7.next(), Types.OTHER)
                .addValue("workspaceId", job.workspaceId(), Types.OTHER)
                .addValue("kind", job.kind().name())
                .addValue("scopeId", job.scopeId(), Types.OTHER)
                .addValue("targetId", job.targetId(), Types.OTHER)
                .addValue("requestedBy", job.requestedBy(), Types.OTHER)
                .addValue("snapshot", job.snapshotJson())
                .addValue("monthKey", YearMonth.from(now.atZone(BILLING_ZONE)).toString())
                .addValue("now", utc(now));
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO job (id, workspace_id, kind, status, scope_id, target_id, requested_by, attempt_count, next_run_at,
                                 snapshot, month_key, created_at, updated_at)
                VALUES (:id, :workspaceId, :kind, 'QUEUED', :scopeId, :targetId, :requestedBy, 0, :now,
                        CAST(:snapshot AS jsonb), :monthKey, :now, :now)
                ON CONFLICT (scope_id) WHERE kind = 'SYNC' AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                DO NOTHING
                RETURNING id
                """, params, UUID.class);
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        return jdbc.queryForList("""
                        SELECT id FROM job
                        WHERE kind = 'SYNC' AND scope_id = :scopeId AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                        """, params, UUID.class)
                .stream()
                .findFirst()
                // 활성 SYNC가 그 사이에 끝났다. 드문 경우라 호출자가 다시 요청한다.
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE));
    }

    /**
     * 슬롯을 먼저 잡고, 그 슬롯이 맡는 종류 중 실행할 때가 된 QUEUED 작업 하나를 RUNNING으로 바꾼다.
     * 슬롯이 사용 중이거나 다른 transaction이 잡고 있으면 기다리지 않고 빈 결과를 돌려준다(시도 횟수를 쓰지 않는다).
     */
    @Transactional
    public Optional<JobLease> claim(SlotKey slot, Collection<JobKind> kinds, String ownerId) {
        for (JobKind kind : kinds) {
            if (kind.slot() != slot) {
                throw new IllegalArgumentException(kind + " 작업은 " + slot + " 슬롯에서 실행하지 않습니다.");
            }
        }
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("slot", slot.name())
                .addValue("kinds", kinds.stream().map(Enum::name).toList())
                .addValue("now", utc(now));
        List<String> freeSlot = jdbc.queryForList("""
                SELECT slot_key FROM execution_slot
                WHERE slot_key = :slot AND job_id IS NULL
                FOR UPDATE SKIP LOCKED
                """, params, String.class);
        if (freeSlot.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> candidates = jdbc.queryForList("""
                SELECT id FROM job
                WHERE status = 'QUEUED' AND kind IN (:kinds) AND next_run_at <= :now
                ORDER BY next_run_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, params, UUID.class);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID jobId = candidates.getFirst();
        params.addValue("jobId", jobId, Types.OTHER)
                .addValue("owner", ownerId)
                .addValue("token", UUID.randomUUID(), Types.OTHER)
                .addValue("lease", utc(now.plus(properties.lease())));
        jdbc.update("""
                UPDATE job SET status = 'RUNNING', attempt_count = attempt_count + 1, owner_id = :owner,
                               run_token = :token, lease_expires_at = :lease, updated_at = :now
                WHERE id = :jobId
                """, params);
        jdbc.update("""
                UPDATE execution_slot SET job_id = :jobId, run_token = :token, lease_expires_at = :lease
                WHERE slot_key = :slot
                """, params);
        return Optional.of(loadLease(jobId));
    }

    /** 작업과 슬롯의 lease를 함께 연장한다. 이미 만료된 lease는 되살리지 않는다. 연장된 만료 시각을 돌려준다. */
    @Transactional
    public Instant heartbeat(JobLease lease) {
        Instant now = clock.instant();
        MapSqlParameterSource params = leaseParams(lease, now);
        lockOwned(lease, params);
        Instant renewed = now.plus(properties.lease());
        params.addValue("lease", utc(renewed));
        jdbc.update("UPDATE execution_slot SET lease_expires_at = :lease WHERE slot_key = :slot", params);
        jdbc.update("UPDATE job SET lease_expires_at = :lease, updated_at = :now WHERE id = :jobId", params);
        return renewed;
    }

    /** 소유권을 확인한 뒤 같은 transaction에서 writes를 실행한다(결과·커서 저장). */
    @Transactional
    public void withLease(JobLease lease, Runnable writes) {
        lockOwned(lease, leaseParams(lease, clock.instant()));
        writes.run();
    }

    @Transactional
    public void advanceStage(JobLease lease, String stage) {
        MapSqlParameterSource params = leaseParams(lease, clock.instant()).addValue("stage", stage);
        lockOwned(lease, params);
        jdbc.update("UPDATE job SET stage = :stage, updated_at = :now WHERE id = :jobId", params);
    }

    /** 결과 저장, SUCCEEDED 전환, 슬롯 반납을 한 transaction에서 한다. 결과 저장이 실패하면 셋 다 되돌린다. */
    @Transactional
    public void succeed(JobLease lease, Runnable resultWriter) {
        MapSqlParameterSource params = leaseParams(lease, clock.instant());
        lockOwned(lease, params);
        resultWriter.run();
        jdbc.update("""
                UPDATE job SET status = 'SUCCEEDED', error_code = NULL, owner_id = NULL, run_token = NULL,
                               lease_expires_at = NULL, updated_at = :now
                WHERE id = :jobId
                """, params);
        releaseSlot(params);
    }

    /** 일시 실패. 시도가 남았으면 RETRY_WAIT, 다 썼으면 FAILED로 바꾸고 슬롯을 반납한다. 바뀐 상태를 돌려준다. */
    @Transactional
    public JobStatus retryLater(JobLease lease, String errorCode, Duration notBefore) {
        Instant now = clock.instant();
        MapSqlParameterSource params = leaseParams(lease, now).addValue("errorCode", errorCode);
        int attemptsUsed = lockOwned(lease, params);
        JobStatus next = toRetryWaitOrFailed(params, attemptsUsed, notBefore, now);
        releaseSlot(params);
        return next;
    }

    /** 다시 해도 소용없는 실패. 시도가 남아도 FAILED로 바꾸고 슬롯을 반납한다. */
    @Transactional
    public void fail(JobLease lease, String errorCode) {
        MapSqlParameterSource params = leaseParams(lease, clock.instant()).addValue("errorCode", errorCode);
        lockOwned(lease, params);
        markFailed(params);
        releaseSlot(params);
    }

    /**
     * lease가 만료된 슬롯을 풀고 그 작업을 RETRY_WAIT(시도를 다 썼으면 FAILED)로 바꾼다.
     * 다른 실행기가 같은 슬롯을 복구 중이면 건너뛴다. 복구한 작업 수를 돌려준다.
     */
    @Transactional
    public int recoverExpired() {
        Instant now = clock.instant();
        List<ExpiredSlot> expired = jdbc.query("""
                        SELECT slot_key, job_id FROM execution_slot
                        WHERE job_id IS NOT NULL AND lease_expires_at <= :now
                        FOR UPDATE SKIP LOCKED
                        """, new MapSqlParameterSource("now", utc(now)),
                (rs, row) -> new ExpiredSlot(rs.getString("slot_key"), rs.getObject("job_id", UUID.class)));
        for (ExpiredSlot slot : expired) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("slot", slot.slotKey())
                    .addValue("jobId", slot.jobId(), Types.OTHER)
                    .addValue("errorCode", LEASE_EXPIRED)
                    .addValue("now", utc(now));
            Integer attemptsUsed = jdbc.queryForObject(
                    "SELECT attempt_count FROM job WHERE id = :jobId FOR UPDATE", params, Integer.class);
            // AI 단계: 이전 실행의 SENT/UNKNOWN 비용 상태를 여기서 먼저 반영하고, 사용량 불명이면 다시 호출하지 않는다.
            toRetryWaitOrFailed(params, attemptsUsed == null ? 0 : attemptsUsed, Duration.ZERO, now);
            releaseSlot(params);
        }
        return expired.size();
    }

    /** 대기가 끝난 RETRY_WAIT 작업을 QUEUED로 되돌린다. */
    @Transactional
    public int promoteDueRetries() {
        // AI 단계: 예산 한도 확인을 여기에 더한다(RETRY_WAIT → QUEUED: 대기 종료와 한도 확인).
        return jdbc.update("""
                UPDATE job SET status = 'QUEUED', updated_at = :now
                WHERE status = 'RETRY_WAIT' AND next_run_at <= :now
                """, new MapSqlParameterSource("now", utc(clock.instant())));
    }

    @Transactional(readOnly = true)
    public Optional<JobView> find(UUID jobId) {
        return jdbc.query("""
                        SELECT id, workspace_id, kind, status, scope_id, target_id, requested_by, stage, attempt_count, owner_id,
                               run_token, lease_expires_at, next_run_at, error_code, month_key
                        FROM job WHERE id = :jobId
                        """, new MapSqlParameterSource().addValue("jobId", jobId, Types.OTHER), JobEngine::toView)
                .stream()
                .findFirst();
    }

    /**
     * 수동 재시도. FAILED 작업만 QUEUED로 되돌리고 같은 jobId·누적 시도·입장월을 유지한다.
     * 되돌린 경우에만 true다. 같은 scope에 활성 SYNC가 이미 있으면 unique 제약에 걸려 예외가 난다.
     */
    @Transactional
    public boolean requeueFailed(UUID jobId) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE job SET status = 'QUEUED', next_run_at = :now, updated_at = :now
                WHERE id = :jobId AND status = 'FAILED'
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId, Types.OTHER)
                .addValue("now", utc(now))) == 1;
    }

    /** 지표용. 상태별 작업 수와 지금 사용 중인 슬롯. */
    @Transactional(readOnly = true)
    public QueueSnapshot queueSnapshot() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        jdbc.query("SELECT status, count(*) AS n FROM job GROUP BY status", Map.of(),
                (RowCallbackHandler) rs -> counts.put(JobStatus.valueOf(rs.getString("status")), rs.getLong("n")));
        Set<SlotKey> busy = EnumSet.noneOf(SlotKey.class);
        jdbc.queryForList("SELECT slot_key FROM execution_slot WHERE job_id IS NOT NULL", Map.of(), String.class)
                .forEach(key -> busy.add(SlotKey.valueOf(key)));
        return new QueueSnapshot(counts, busy);
    }

    /** 이 lease가 아직 소유자인지 슬롯 → 작업 순서로 잠그며 확인하고, 지금까지 쓴 시도 횟수를 돌려준다. */
    private int lockOwned(JobLease lease, MapSqlParameterSource params) {
        List<String> ownedSlot = jdbc.queryForList("""
                SELECT slot_key FROM execution_slot
                WHERE slot_key = :slot AND job_id = :jobId AND run_token = :token AND lease_expires_at > :now
                FOR UPDATE
                """, params, String.class);
        if (ownedSlot.isEmpty()) {
            throw new LeaseLostException(lease.jobId());
        }
        List<Integer> attemptsUsed = jdbc.queryForList("""
                SELECT attempt_count FROM job
                WHERE id = :jobId AND status = 'RUNNING' AND run_token = :token AND lease_expires_at > :now
                FOR UPDATE
                """, params, Integer.class);
        if (attemptsUsed.isEmpty()) {
            throw new LeaseLostException(lease.jobId());
        }
        return attemptsUsed.getFirst();
    }

    private JobStatus toRetryWaitOrFailed(MapSqlParameterSource params, int attemptsUsed, Duration notBefore,
                                          Instant now) {
        if (!retryPolicy.hasAttemptsLeft(attemptsUsed)) {
            markFailed(params);
            return JobStatus.FAILED;
        }
        params.addValue("nextRun", utc(now.plus(retryPolicy.delayAfter(attemptsUsed, notBefore))));
        jdbc.update("""
                UPDATE job SET status = 'RETRY_WAIT', error_code = :errorCode, next_run_at = :nextRun,
                               owner_id = NULL, run_token = NULL, lease_expires_at = NULL, updated_at = :now
                WHERE id = :jobId
                """, params);
        return JobStatus.RETRY_WAIT;
    }

    private void markFailed(MapSqlParameterSource params) {
        jdbc.update("""
                UPDATE job SET status = 'FAILED', error_code = :errorCode, owner_id = NULL, run_token = NULL,
                               lease_expires_at = NULL, updated_at = :now
                WHERE id = :jobId
                """, params);
    }

    private void releaseSlot(MapSqlParameterSource params) {
        jdbc.update("""
                UPDATE execution_slot SET job_id = NULL, run_token = NULL, lease_expires_at = NULL
                WHERE slot_key = :slot
                """, params);
    }

    private JobLease loadLease(UUID jobId) {
        return jdbc.queryForObject("""
                SELECT id, workspace_id, kind, scope_id, target_id, stage, attempt_count,
                       CAST(snapshot AS text) AS snapshot, owner_id, run_token, lease_expires_at,
                       next_run_at, updated_at
                FROM job WHERE id = :jobId
                """, new MapSqlParameterSource().addValue("jobId", jobId, Types.OTHER), (rs, row) -> {
                    JobKind kind = JobKind.valueOf(rs.getString("kind"));
                    // 선점 직후 읽으므로 updated_at이 곧 선점 시각이다.
                    return new JobLease(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                            kind, kind.slot(), rs.getObject("scope_id", UUID.class),
                            rs.getObject("target_id", UUID.class), rs.getString("stage"), rs.getInt("attempt_count"),
                            rs.getString("snapshot"), rs.getString("owner_id"), rs.getObject("run_token", UUID.class),
                            instant(rs, "lease_expires_at"), instant(rs, "next_run_at"), instant(rs, "updated_at"));
                });
    }

    private static MapSqlParameterSource leaseParams(JobLease lease, Instant now) {
        return new MapSqlParameterSource()
                .addValue("slot", lease.slot().name())
                .addValue("jobId", lease.jobId(), Types.OTHER)
                .addValue("token", lease.runToken(), Types.OTHER)
                .addValue("now", utc(now));
    }

    private static JobView toView(ResultSet rs, int row) throws SQLException {
        return new JobView(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                JobKind.valueOf(rs.getString("kind")),
                JobStatus.valueOf(rs.getString("status")),
                rs.getObject("scope_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getObject("requested_by", UUID.class),
                rs.getString("stage"),
                rs.getInt("attempt_count"),
                rs.getString("owner_id"),
                rs.getObject("run_token", UUID.class),
                instant(rs, "lease_expires_at"),
                instant(rs, "next_run_at"),
                rs.getString("error_code"),
                rs.getString("month_key"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record ExpiredSlot(String slotKey, UUID jobId) {
    }
}
