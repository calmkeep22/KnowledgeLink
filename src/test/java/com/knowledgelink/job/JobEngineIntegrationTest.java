package com.knowledgelink.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.application.NewJob;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import com.knowledgelink.job.persistence.JobEngine;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.MutableClock;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 작업 상태 전환(명세 03 4장)과 DB가 지키는 작업·슬롯 규칙. */
@IntegrationTest
class JobEngineIntegrationTest {

    /** 서울 기준 2026-09-01 00:30. UTC로는 아직 8월이다. */
    private static final Instant T0 = Instant.parse("2026-08-31T15:30:00Z");
    private static final Set<JobKind> AI_KINDS =
            EnumSet.of(JobKind.ANALYSIS, JobKind.QUESTION, JobKind.CARD, JobKind.LINK_SUGGEST);
    private static final Set<JobKind> SYNC_KINDS = EnumSet.of(JobKind.SYNC, JobKind.RECONCILE);

    @Autowired
    JobEngine engine;

    @Autowired
    JobProperties properties;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Workspace org;
    private SourceScope pay;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        clock.set(T0);
        org = fixtures.workspace("Job Org");
        pay = fixtures.scope(fixtures.jiraConnection(org, "jira", "site-1"), "10000", "PAY");
    }

    @Test
    void 넣은_작업은_QUEUED로_저장되고_입장월은_서울_기준이다() {
        UUID id = enqueueAnalysis();

        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attemptCount()).isZero();
        assertThat(job.nextRunAt()).isEqualTo(T0);
        assertThat(job.monthKey()).isEqualTo("2026-09");
    }

    @Test
    void scope당_활성_SYNC는_하나이고_끝나면_새로_넣을_수_있다() {
        UUID first = enqueueSync();
        assertThat(enqueueSync()).isEqualTo(first);

        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();
        assertThat(enqueueSync()).as("실행 중인 SYNC도 활성이다").isEqualTo(first);
        engine.succeed(lease, () -> { });

        assertThat(enqueueSync()).isNotEqualTo(first);
        assertThat(fixtures.count("job")).isEqualTo(2);
    }

    @Test
    void 다른_조직의_scope로는_작업을_만들_수_없다() {
        Workspace other = fixtures.workspace("Other Org");

        assertThatThrownBy(() -> engine.enqueue(NewJob.forScope(other.getId(), JobKind.SYNC, pay.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 선점하면_작업과_슬롯에_같은_소유권이_기록되고_시도_횟수가_는다() {
        UUID id = enqueueAnalysis();

        JobLease lease = engine.claim(SlotKey.AI, AI_KINDS, "worker-1").orElseThrow();

        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.ownerId()).isEqualTo("worker-1");
        assertThat(job.runToken()).isEqualTo(lease.runToken());
        assertThat(job.leaseExpiresAt()).isEqualTo(T0.plus(properties.lease()));
        assertThat(fixtures.slot(SlotKey.AI)).containsEntry("job_id", id).containsEntry("run_token", lease.runToken());
    }

    @Test
    void 성공하면_결과와_상태를_함께_저장하고_슬롯을_반납한다() {
        UUID id = enqueueSync();
        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        engine.succeed(lease, () -> fixtures.markSynced(pay, T0));

        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.runToken()).isNull();
        assertThat(fixtures.lastSyncedAt(pay)).isEqualTo(T0);
        assertThat(fixtures.slot(SlotKey.SYNC).get("job_id")).isNull();
    }

    @Test
    void 결과_저장이_실패하면_상태와_슬롯도_그대로다() {
        UUID id = enqueueSync();
        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        assertThatThrownBy(() -> engine.succeed(lease, () -> {
            fixtures.markSynced(pay, T0);
            throw new IllegalStateException("결과 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(engine.find(id).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(fixtures.lastSyncedAt(pay)).isNull();
        assertThat(fixtures.slot(SlotKey.SYNC)).containsEntry("job_id", id);
    }

    @Test
    void 일시_실패는_백오프_뒤에_다시_실행되고_시도를_다_쓰면_FAILED다() {
        UUID id = enqueueAnalysis();

        JobLease first = claimAi();
        assertThat(engine.retryLater(first, "AI_TIMEOUT", Duration.ZERO)).isEqualTo(JobStatus.RETRY_WAIT);
        JobView waiting = engine.find(id).orElseThrow();
        assertThat(waiting.errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(waiting.nextRunAt()).isBetween(T0.plusSeconds(15), T0.plusSeconds(30));
        assertThat(fixtures.slot(SlotKey.AI).get("job_id")).isNull();
        assertThat(engine.claim(SlotKey.AI, AI_KINDS, "worker-1")).as("대기 중에는 선점하지 않는다").isEmpty();

        clock.advance(Duration.ofSeconds(30));
        assertThat(engine.promoteDueRetries()).isEqualTo(1);
        JobLease second = claimAi();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(engine.retryLater(second, "AI_TIMEOUT", Duration.ZERO)).isEqualTo(JobStatus.RETRY_WAIT);

        clock.advance(Duration.ofSeconds(60));
        engine.promoteDueRetries();
        JobLease third = claimAi();
        assertThat(engine.retryLater(third, "AI_TIMEOUT", Duration.ZERO)).isEqualTo(JobStatus.FAILED);

        JobView failed = engine.find(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(3);
        assertThat(failed.errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(fixtures.slot(SlotKey.AI).get("job_id")).isNull();
    }

    @Test
    void Retry_After가_백오프보다_길면_그만큼_기다린다() {
        UUID id = enqueueSync();
        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        engine.retryLater(lease, "SOURCE_RATE_LIMITED", Duration.ofMinutes(10));

        assertThat(engine.find(id).orElseThrow().nextRunAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void 영구_실패는_시도가_남아도_바로_FAILED다() {
        UUID id = enqueueSync();
        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        engine.fail(lease, "SOURCE_AUTH_FAILED");

        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.errorCode()).isEqualTo("SOURCE_AUTH_FAILED");
        assertThat(fixtures.slot(SlotKey.SYNC).get("job_id")).isNull();
    }

    @Test
    void 슬롯을_못_잡은_작업은_QUEUED로_남고_시도를_쓰지_않는다() {
        UUID first = enqueueAnalysis();
        UUID second = engine.enqueue(NewJob.forTarget(org.getId(), JobKind.QUESTION, UUID.randomUUID()));

        assertThat(claimAi().jobId()).as("먼저 들어온 작업부터").isEqualTo(first);
        assertThat(engine.claim(SlotKey.AI, AI_KINDS, "worker-2")).isEmpty();

        JobView waiting = engine.find(second).orElseThrow();
        assertThat(waiting.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(waiting.attemptCount()).isZero();
    }

    @Test
    void 슬롯은_SYNC_INDEX_AI_세_개이고_작업과_어긋난_소유_정보는_DB가_거절한다() {
        UUID id = enqueueAnalysis();

        assertThat(jdbcTemplate.queryForList("SELECT slot_key FROM execution_slot ORDER BY slot_key", String.class))
                .containsExactly("AI", "INDEX", "SYNC");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE job SET status = 'RUNNING' WHERE id = ?", id))
                .as("RUNNING인데 소유자·run_token·lease가 없다")
                .isInstanceOf(DataIntegrityViolationException.class);

        claimAi();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE execution_slot SET run_token = ? WHERE slot_key = 'AI'", UUID.randomUUID()))
                .as("슬롯의 run_token이 작업과 다르다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID enqueueSync() {
        return engine.enqueue(NewJob.forScope(org.getId(), JobKind.SYNC, pay.getId()));
    }

    private UUID enqueueAnalysis() {
        return engine.enqueue(NewJob.forTarget(org.getId(), JobKind.ANALYSIS, UUID.randomUUID()));
    }

    private JobLease claimAi() {
        return engine.claim(SlotKey.AI, AI_KINDS, "worker-1").orElseThrow();
    }
}
