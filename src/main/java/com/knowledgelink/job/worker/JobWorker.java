package com.knowledgelink.job.worker;

import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.JobOutcome;
import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.LeaseLostException;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import com.knowledgelink.job.persistence.JobEngine;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 작업 실행기. 주기마다 만료된 lease를 복구하고, 대기가 끝난 재시도를 QUEUED로 되돌린 뒤,
 * 비어 있는 슬롯마다 작업을 하나 선점해 실행한다. 실행 중에는 heartbeat로 lease를 연장한다.
 *
 * <p>이 인스턴스가 멈추면(종료·장애) 실행 중이던 작업은 lease가 만료된 뒤 다른 실행기나 재시작한 실행기가 복구한다.
 * 이때 쓴 시도 횟수는 돌려주지 않는다.
 */
@Slf4j
public class JobWorker implements SmartLifecycle {

    /** handler가 예외를 던졌을 때 기록하는 오류 코드. 일시 실패로 보고 재시도한다. */
    public static final String HANDLER_ERROR = "HANDLER_ERROR";

    /** 상태별 작업 수·슬롯 사용 지표를 갱신하는 간격. 지표 수집 주기와 비슷하게 둔다. */
    private static final Duration QUEUE_METRICS_INTERVAL = Duration.ofSeconds(15);

    private final JobEngine engine;
    private final JobHandlers handlers;
    private final JobMetrics metrics;
    private final JobProperties properties;
    private final String ownerId;
    private final ScheduledExecutorService scheduler;
    private final Executor executor;
    private final Set<SlotKey> busySlots = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    /**
     * @param scheduler 주기 실행과 heartbeat용
     * @param executor  handler 실행용. 슬롯마다 한 작업씩 동시에 실행된다.
     */
    public JobWorker(JobEngine engine, JobHandlers handlers, JobMetrics metrics, JobProperties properties,
                     String ownerId, ScheduledExecutorService scheduler, Executor executor) {
        this.engine = engine;
        this.handlers = handlers;
        this.metrics = metrics;
        this.properties = properties;
        this.ownerId = ownerId;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    @Override
    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(this::tickSafely, 0, properties.pollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::refreshQueueMetricsSafely, 0, QUEUE_METRICS_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("job worker started ownerId={}", ownerId);
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
        log.info("job worker stopped ownerId={}", ownerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 주기 실행 한 번. 테스트에서도 직접 호출한다. */
    public void tick() {
        metrics.recovered(engine.recoverExpired());
        engine.promoteDueRetries();
        for (SlotKey slot : SlotKey.values()) {
            Set<JobKind> kinds = handlers.kindsFor(slot);
            if (kinds.isEmpty() || !busySlots.add(slot)) {
                continue;
            }
            Optional<JobLease> lease;
            try {
                lease = engine.claim(slot, kinds, ownerId);
            } catch (RuntimeException e) {
                busySlots.remove(slot);
                throw e;
            }
            if (lease.isEmpty()) {
                busySlots.remove(slot);
                continue;
            }
            metrics.claimed(lease.get());
            try {
                executor.execute(() -> {
                    try {
                        run(lease.get());
                    } finally {
                        busySlots.remove(slot);
                    }
                });
            } catch (RejectedExecutionException e) {
                // 종료 중이다. 선점한 작업은 lease가 만료된 뒤 복구된다.
                busySlots.remove(slot);
            }
        }
    }

    /** 상태별 작업 수와 슬롯 사용 여부를 지표에 반영한다. */
    public void refreshQueueMetrics() {
        metrics.update(engine.queueSnapshot());
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("job worker tick failed ownerId={}", ownerId, e);
        }
    }

    private void refreshQueueMetricsSafely() {
        try {
            refreshQueueMetrics();
        } catch (RuntimeException e) {
            log.warn("job queue metrics refresh failed", e);
        }
    }

    private void run(JobLease lease) {
        LeasedJobContext context = new LeasedJobContext(engine, lease);
        long beatMillis = properties.heartbeat().toMillis();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> beat(context), beatMillis, beatMillis, TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        String outcome = JobMetrics.ERROR;
        try {
            outcome = finish(lease, execute(context));
        } catch (LeaseLostException e) {
            outcome = JobMetrics.LEASE_LOST;
            log.warn("job lease lost, result discarded jobId={} kind={} attempt={}",
                    lease.jobId(), lease.kind(), lease.attemptCount());
        } finally {
            heartbeat.cancel(false);
            metrics.finished(lease.kind(), outcome, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private JobOutcome execute(LeasedJobContext context) {
        JobLease lease = context.lease();
        try {
            return handlers.handlerFor(lease.kind()).execute(context);
        } catch (LeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("job handler failed jobId={} kind={} attempt={}",
                    lease.jobId(), lease.kind(), lease.attemptCount(), e);
            return JobOutcome.retryLater(HANDLER_ERROR);
        }
    }

    /** 결과에 맞게 상태를 바꾸고, 지표에 남길 결과 이름을 돌려준다. */
    private String finish(JobLease lease, JobOutcome outcome) {
        return switch (outcome) {
            case JobOutcome.Succeeded succeeded -> {
                engine.succeed(lease, succeeded.resultWriter());
                yield JobMetrics.SUCCEEDED;
            }
            case JobOutcome.RetryLater retry ->
                    engine.retryLater(lease, retry.errorCode(), retry.notBefore()) == JobStatus.FAILED
                            ? JobMetrics.FAILED
                            : JobMetrics.RETRY;
            case JobOutcome.Failed failed -> {
                engine.fail(lease, failed.errorCode());
                yield JobMetrics.FAILED;
            }
        };
    }

    private void beat(LeasedJobContext context) {
        try {
            engine.heartbeat(context.lease());
        } catch (LeaseLostException e) {
            context.markLeaseLost();
            // 예외를 던지면 이 heartbeat 예약이 멈춘다.
            throw e;
        } catch (RuntimeException e) {
            // DB 일시 오류. lease가 끝나기 전에 다음 heartbeat가 성공하면 된다.
            log.warn("job heartbeat failed jobId={}", context.lease().jobId(), e);
        }
    }
}
