package com.knowledgelink.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.job.application.JobContext;
import com.knowledgelink.job.application.JobHandler;
import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.JobOutcome;
import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.application.LeaseLostException;
import com.knowledgelink.job.application.NewJob;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import com.knowledgelink.job.persistence.JobEngine;
import com.knowledgelink.job.worker.JobHandlers;
import com.knowledgelink.job.worker.JobMetrics;
import com.knowledgelink.job.worker.JobWorker;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.MutableClock;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 재시작 복구(T24)와 lease 만료 후 재선점(T25). 실행기는 테스트 스레드에서 한 주기씩 직접 돌린다. */
@IntegrationTest
class JobLeaseRecoveryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-15T01:00:00Z");
    private static final Set<JobKind> SYNC_KINDS = EnumSet.of(JobKind.SYNC);

    @Autowired
    JobEngine engine;

    @Autowired
    JobProperties properties;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    MutableClock clock;

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private Workspace org;
    private SourceScope pay;
    private SimpleMeterRegistry meters;
    private JobMetrics metrics;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        clock.set(T0);
        meters = new SimpleMeterRegistry();
        metrics = new JobMetrics(meters);
        org = fixtures.workspace("Recovery Org");
        pay = fixtures.scope(fixtures.jiraConnection(org, "jira", "site-1"), "10000", "PAY");
    }

    @AfterEach
    void tearDown() {
        heartbeatScheduler.shutdownNow();
    }

    @Test
    void T24_QUEUED로_저장한_직후_재시작해도_새_실행기가_처리한다() {
        UUID id = enqueueSync();
        List<UUID> executed = new ArrayList<>();

        worker("after-restart", handler(context -> {
            executed.add(context.lease().jobId());
            return JobOutcome.succeeded(() -> fixtures.markSynced(pay, T0));
        })).tick();

        assertThat(executed).containsExactly(id);
        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(fixtures.lastSyncedAt(pay)).isEqualTo(T0);
        assertThat(fixtures.slot(SlotKey.SYNC).get("job_id")).isNull();
        assertThat(meters.get("kl.jobs.claimed").tag("kind", "SYNC").counter().count()).isEqualTo(1);
        assertThat(executions("SYNC", JobMetrics.SUCCEEDED)).isEqualTo(1);
    }

    @Test
    void T24_실행_중_종료되면_lease_만료_후_복구되어_다시_실행된다() {
        UUID id = enqueueSync();
        engine.claim(SlotKey.SYNC, SYNC_KINDS, "crashed").orElseThrow();
        JobWorker restarted = worker("restarted", handler(context -> JobOutcome.succeeded()));

        restarted.tick();
        assertThat(engine.find(id).orElseThrow().ownerId()).as("lease가 남아 있으면 건드리지 않는다").isEqualTo("crashed");

        clock.advance(properties.lease().plusSeconds(1));
        restarted.tick();
        JobView recovered = engine.find(id).orElseThrow();
        assertThat(recovered.status()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(recovered.errorCode()).isEqualTo(JobEngine.LEASE_EXPIRED);
        assertThat(recovered.ownerId()).isNull();
        assertThat(fixtures.slot(SlotKey.SYNC).get("job_id")).isNull();
        assertThat(meters.get("kl.jobs.recovered").counter().count()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        restarted.tick();
        JobView done = engine.find(id).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.attemptCount()).isEqualTo(2);
    }

    @Test
    void T25_lease_만료_후_재선점되면_이전_실행기의_heartbeat_결과_커서_완료_슬롯_해제를_거절한다() {
        UUID id = enqueueSync();
        JobLease old = engine.claim(SlotKey.SYNC, SYNC_KINDS, "old").orElseThrow();

        clock.advance(properties.lease().plusSeconds(1));
        assertThat(engine.recoverExpired()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30));
        engine.promoteDueRetries();
        JobLease current = engine.claim(SlotKey.SYNC, SYNC_KINDS, "new").orElseThrow();
        assertThat(current.jobId()).isEqualTo(id);
        assertThat(current.runToken()).isNotEqualTo(old.runToken());

        assertThatThrownBy(() -> engine.heartbeat(old)).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> engine.withLease(old, () -> fixtures.markSynced(pay, T0)))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> engine.advanceStage(old, "FETCH")).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> engine.succeed(old, () -> fixtures.markSynced(pay, T0)))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> engine.retryLater(old, "OLD_WORKER", Duration.ZERO))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> engine.fail(old, "OLD_WORKER")).isInstanceOf(LeaseLostException.class);

        assertThat(fixtures.lastSyncedAt(pay)).isNull();
        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.ownerId()).isEqualTo("new");
        assertThat(job.runToken()).isEqualTo(current.runToken());
        assertThat(job.stage()).isNull();
        assertThat(job.errorCode()).isEqualTo(JobEngine.LEASE_EXPIRED);
        assertThat(fixtures.slot(SlotKey.SYNC))
                .containsEntry("job_id", id)
                .containsEntry("run_token", current.runToken());

        engine.heartbeat(current);
        engine.succeed(current, () -> fixtures.markSynced(pay, T0));
        assertThat(engine.find(id).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void 만료된_lease는_heartbeat로_되살리지_않는다() {
        UUID id = enqueueSync();
        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        clock.advance(properties.lease());

        assertThatThrownBy(() -> engine.heartbeat(lease)).isInstanceOf(LeaseLostException.class);
        assertThat(engine.find(id).orElseThrow().leaseExpiresAt()).isEqualTo(T0.plus(properties.lease()));
    }

    @Test
    void heartbeat는_작업과_슬롯의_lease를_함께_연장한다() {
        UUID id = enqueueSync();
        JobLease lease = engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        clock.advance(properties.heartbeat());
        Instant renewed = engine.heartbeat(lease);

        assertThat(renewed).isEqualTo(T0.plus(properties.heartbeat()).plus(properties.lease()));
        assertThat(engine.find(id).orElseThrow().leaseExpiresAt()).isEqualTo(renewed);
        assertThat(((Timestamp) fixtures.slot(SlotKey.SYNC).get("lease_expires_at")).toInstant()).isEqualTo(renewed);
    }

    @Test
    void 실행_중_소유권을_잃은_실행기의_결과는_저장되지_않는다() {
        UUID id = enqueueSync();
        AtomicReference<JobLease> takeover = new AtomicReference<>();

        worker("slow", handler(context -> {
            // 외부 호출이 lease보다 오래 걸려, 그 사이 다른 실행기가 복구하고 재선점했다.
            clock.advance(properties.lease().plusSeconds(1));
            engine.recoverExpired();
            clock.advance(Duration.ofSeconds(30));
            engine.promoteDueRetries();
            takeover.set(engine.claim(SlotKey.SYNC, SYNC_KINDS, "other").orElseThrow());
            return JobOutcome.succeeded(() -> fixtures.markSynced(pay, T0));
        })).tick();

        assertThat(fixtures.lastSyncedAt(pay)).isNull();
        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.ownerId()).isEqualTo("other");
        assertThat(job.runToken()).isEqualTo(takeover.get().runToken());
        assertThat(executions("SYNC", JobMetrics.LEASE_LOST)).isEqualTo(1);
    }

    @Test
    void handler가_예외를_던지면_일시_실패로_기록하고_슬롯을_반납한다() {
        UUID id = enqueueSync();

        worker("worker-1", handler(context -> {
            throw new IllegalStateException("예상하지 못한 오류");
        })).tick();

        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(job.errorCode()).isEqualTo(JobWorker.HANDLER_ERROR);
        assertThat(fixtures.slot(SlotKey.SYNC).get("job_id")).isNull();
        assertThat(executions("SYNC", JobMetrics.RETRY)).isEqualTo(1);
    }

    @Test
    void 큐_지표는_상태별_작업_수와_사용_중인_슬롯을_보여준다() {
        enqueueSync();
        engine.enqueue(NewJob.forTarget(org.getId(), JobKind.INDEX, UUID.randomUUID()));
        engine.claim(SlotKey.SYNC, SYNC_KINDS, "worker-1").orElseThrow();

        worker("worker-2", handler(context -> JobOutcome.succeeded())).refreshQueueMetrics();

        assertThat(meters.get("kl.jobs.count").tag("status", "RUNNING").gauge().value()).isEqualTo(1);
        assertThat(meters.get("kl.jobs.count").tag("status", "QUEUED").gauge().value()).isEqualTo(1);
        assertThat(meters.get("kl.jobs.slot.busy").tag("slot", "SYNC").gauge().value()).isEqualTo(1);
        assertThat(meters.get("kl.jobs.slot.busy").tag("slot", "AI").gauge().value()).isZero();
    }

    private long executions(String kind, String outcome) {
        return meters.get("kl.jobs.execution").tags("kind", kind, "outcome", outcome).timer().count();
    }

    private UUID enqueueSync() {
        return engine.enqueue(NewJob.forScope(org.getId(), JobKind.SYNC, pay.getId()));
    }

    /** 새로 만든 실행기는 메모리 상태가 없으므로 재시작한 프로세스와 같다. handler는 테스트 스레드에서 실행된다. */
    private JobWorker worker(String ownerId, JobHandler handler) {
        return new JobWorker(engine, new JobHandlers(List.of(handler)), metrics, properties, ownerId,
                heartbeatScheduler, Runnable::run);
    }

    private static JobHandler handler(Function<JobContext, JobOutcome> body) {
        return new JobHandler() {
            @Override
            public Set<JobKind> kinds() {
                return SYNC_KINDS;
            }

            @Override
            public JobOutcome execute(JobContext context) {
                return body.apply(context);
            }
        };
    }
}
