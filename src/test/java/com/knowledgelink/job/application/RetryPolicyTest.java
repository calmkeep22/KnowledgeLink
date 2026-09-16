package com.knowledgelink.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    /** jitter 범위의 가장 작은 값을 고르는 난수. */
    private static final RandomGenerator LOWEST = () -> 0L;

    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(300), LOWEST);

    @Test
    void 최초_포함_세_번까지만_실행한다() {
        assertThat(policy.hasAttemptsLeft(1)).isTrue();
        assertThat(policy.hasAttemptsLeft(2)).isTrue();
        assertThat(policy.hasAttemptsLeft(3)).isFalse();
    }

    @Test
    void 대기_상한은_두_배씩_늘고_최대값을_넘지_않는다() {
        assertThat(policy.delayAfter(1, Duration.ZERO)).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.delayAfter(2, Duration.ZERO)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayAfter(3, Duration.ZERO)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayAfter(5, Duration.ZERO)).isEqualTo(Duration.ofSeconds(150));
        assertThat(policy.delayAfter(40, Duration.ZERO)).isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    void jitter는_상한의_절반과_상한_사이다() {
        RetryPolicy jittered = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(300), new Random(42));

        for (int attempt = 1; attempt <= 6; attempt++) {
            long capSeconds = Math.min(300, 30L << (attempt - 1));
            for (int i = 0; i < 200; i++) {
                assertThat(jittered.delayAfter(attempt, Duration.ZERO))
                        .isBetween(Duration.ofMillis(capSeconds * 500), Duration.ofSeconds(capSeconds));
            }
        }
    }

    @Test
    void Retry_After가_백오프보다_길면_그만큼_기다린다() {
        assertThat(policy.delayAfter(1, Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.delayAfter(1, Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void 잘못된_설정은_거절한다() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(30), Duration.ofSeconds(300), LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(10), LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
