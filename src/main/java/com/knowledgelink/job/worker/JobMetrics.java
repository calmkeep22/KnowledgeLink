package com.knowledgelink.job.worker;

import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.QueueSnapshot;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 작업 엔진 지표. 이름은 {@code kl.jobs.*}이다.
 *
 * <p>태그는 작업 종류·결과·상태·슬롯처럼 값이 몇 개로 정해진 것만 쓴다. 작업 ID처럼 값이 계속 늘어나는 것을
 * 태그로 쓰면 시계열이 끝없이 늘어난다.
 */
public class JobMetrics {

    public static final String SUCCEEDED = "succeeded";
    public static final String RETRY = "retry";
    public static final String FAILED = "failed";
    /** 실행 중에 소유권을 잃어 결과를 버렸다. */
    public static final String LEASE_LOST = "lease_lost";
    /** 결과 저장이나 상태 전환 자체가 실패했다(DB 오류 등). lease가 끝나면 복구된다. */
    public static final String ERROR = "error";

    private final MeterRegistry registry;
    private final Counter recovered;
    private final Map<JobStatus, AtomicLong> jobsByStatus = new EnumMap<>(JobStatus.class);
    private final Map<SlotKey, AtomicInteger> busySlots = new EnumMap<>(SlotKey.class);

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.recovered = Counter.builder("kl.jobs.recovered")
                .description("lease가 만료되어 복구한 작업 수")
                .register(registry);
        for (JobStatus status : JobStatus.values()) {
            AtomicLong value = new AtomicLong();
            jobsByStatus.put(status, value);
            Gauge.builder("kl.jobs.count", value, AtomicLong::get)
                    .description("상태별 작업 수")
                    .tag("status", status.name())
                    .register(registry);
        }
        for (SlotKey slot : SlotKey.values()) {
            AtomicInteger value = new AtomicInteger();
            busySlots.put(slot, value);
            Gauge.builder("kl.jobs.slot.busy", value, AtomicInteger::get)
                    .description("슬롯을 사용 중이면 1")
                    .tag("slot", slot.name())
                    .register(registry);
        }
    }

    /** 선점. 실행 가능해진 시각부터 선점까지(슬롯 대기 포함)를 대기 시간으로 기록한다. */
    public void claimed(JobLease lease) {
        String kind = lease.kind().name();
        Counter.builder("kl.jobs.claimed")
                .description("선점한 작업 수")
                .tag("kind", kind)
                .register(registry)
                .increment();
        Duration waited = Duration.between(lease.eligibleAt(), lease.claimedAt());
        Timer.builder("kl.jobs.queue.wait")
                .description("실행 가능해진 뒤 선점될 때까지 걸린 시간")
                .tag("kind", kind)
                .publishPercentileHistogram()
                .register(registry)
                .record(waited.isNegative() ? Duration.ZERO : waited);
    }

    /** handler 실행부터 상태 전환까지 걸린 시간과 결과. */
    public void finished(JobKind kind, String outcome, Duration took) {
        Timer.builder("kl.jobs.execution")
                .description("handler 실행부터 상태 전환까지 걸린 시간")
                .tags("kind", kind.name(), "outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(took);
    }

    public void recovered(int count) {
        if (count > 0) {
            recovered.increment(count);
        }
    }

    public void update(QueueSnapshot snapshot) {
        jobsByStatus.forEach((status, value) -> value.set(snapshot.jobsByStatus().getOrDefault(status, 0L)));
        busySlots.forEach((slot, value) -> value.set(snapshot.busySlots().contains(slot) ? 1 : 0));
    }
}
