package com.knowledgelink.job.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.QueueSnapshot;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JobMetricsTest {

    private static final Instant T0 = Instant.parse("2026-09-16T01:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JobMetrics metrics = new JobMetrics(meters);

    @Test
    void 상태별_작업_수와_슬롯_사용은_처음부터_0으로_보인다() {
        for (JobStatus status : JobStatus.values()) {
            assertThat(meters.get("kl.jobs.count").tag("status", status.name()).gauge().value()).isZero();
        }
        for (SlotKey slot : SlotKey.values()) {
            assertThat(meters.get("kl.jobs.slot.busy").tag("slot", slot.name()).gauge().value()).isZero();
        }
        assertThat(meters.get("kl.jobs.recovered").counter().count()).isZero();
    }

    @Test
    void 큐_상태를_반영하고_없는_상태는_0으로_되돌린다() {
        metrics.update(new QueueSnapshot(Map.of(JobStatus.QUEUED, 3L, JobStatus.RUNNING, 1L), Set.of(SlotKey.AI)));
        metrics.update(new QueueSnapshot(Map.of(JobStatus.QUEUED, 2L), Set.of()));

        assertThat(meters.get("kl.jobs.count").tag("status", "QUEUED").gauge().value()).isEqualTo(2);
        assertThat(meters.get("kl.jobs.count").tag("status", "RUNNING").gauge().value()).isZero();
        assertThat(meters.get("kl.jobs.slot.busy").tag("slot", "AI").gauge().value()).isZero();
    }

    @Test
    void 선점하면_실행_가능해진_뒤_기다린_시간을_기록한다() {
        metrics.claimed(lease(JobKind.ANALYSIS, T0, T0.plusSeconds(7)));
        // 시계가 어긋나 선점 시각이 앞서도 음수를 기록하지 않는다.
        metrics.claimed(lease(JobKind.ANALYSIS, T0.plusSeconds(5), T0));

        assertThat(meters.get("kl.jobs.claimed").tag("kind", "ANALYSIS").counter().count()).isEqualTo(2);
        assertThat(meters.get("kl.jobs.queue.wait").tag("kind", "ANALYSIS").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(7);
    }

    @Test
    void 실행_결과는_종류와_결과별로_나눠_기록한다() {
        metrics.finished(JobKind.SYNC, JobMetrics.SUCCEEDED, Duration.ofSeconds(2));
        metrics.finished(JobKind.SYNC, JobMetrics.RETRY, Duration.ofSeconds(1));
        metrics.recovered(0);
        metrics.recovered(2);

        assertThat(meters.get("kl.jobs.execution").tags("kind", "SYNC", "outcome", "succeeded").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("kl.jobs.execution").tags("kind", "SYNC", "outcome", "retry").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("kl.jobs.recovered").counter().count()).isEqualTo(2);
    }

    private static JobLease lease(JobKind kind, Instant eligibleAt, Instant claimedAt) {
        return new JobLease(UUID.randomUUID(), UUID.randomUUID(), kind, kind.slot(), null, UUID.randomUUID(), null, 1,
                "{}", "worker-1", UUID.randomUUID(), claimedAt.plusSeconds(120), eligibleAt, claimedAt);
    }
}
