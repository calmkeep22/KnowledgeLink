package com.knowledgelink.job.application;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 작업 실행 설정. 기본값은 명세 제안값이다(lease 120초·30초 갱신, 최초 포함 3회, 30초~300초 backoff).
 */
@Validated
@ConfigurationProperties("kl.jobs")
public record JobProperties(
        @DefaultValue("120s") Duration lease,
        @DefaultValue("30s") Duration heartbeat,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("30s") Duration retryBase,
        @DefaultValue("300s") Duration retryMax,
        @DefaultValue Worker worker) {

    public JobProperties {
        if (heartbeat.isNegative() || heartbeat.isZero() || heartbeat.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("kl.jobs.heartbeat는 0보다 크고 lease보다 짧아야 합니다.");
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("kl.jobs.poll-interval은 0보다 커야 합니다.");
        }
    }

    /** enabled=false면 이 인스턴스는 작업을 실행하지 않는다. 통합 테스트는 엔진을 직접 호출해 시점을 통제한다. */
    public record Worker(@DefaultValue("true") boolean enabled) {
    }
}
