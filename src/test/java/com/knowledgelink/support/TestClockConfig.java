package com.knowledgelink.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 운영 {@code Clock} 빈 대신 테스트 시계를 주입한다. 생성 시각 기록(auditing)도 이 시계를 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
