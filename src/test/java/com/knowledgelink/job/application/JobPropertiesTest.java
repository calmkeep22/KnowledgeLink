package com.knowledgelink.job.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JobPropertiesTest {

    @Test
    void heartbeat가_lease보다_짧지_않으면_시작하지_않는다() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(120), Duration.ofSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(120), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JobProperties properties(Duration lease, Duration heartbeat) {
        return new JobProperties(lease, heartbeat, Duration.ofSeconds(2), 3, Duration.ofSeconds(30),
                Duration.ofSeconds(300), new JobProperties.Worker(true));
    }
}
