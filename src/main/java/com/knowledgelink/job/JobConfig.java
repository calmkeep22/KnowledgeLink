package com.knowledgelink.job;

import com.knowledgelink.job.application.JobHandler;
import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.RetryPolicy;
import com.knowledgelink.job.persistence.JobEngine;
import com.knowledgelink.job.worker.JobHandlers;
import com.knowledgelink.job.worker.JobMetrics;
import com.knowledgelink.job.worker.JobWorker;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 작업 모듈 조립. 재시도 정책·handler 목록·지표는 항상 등록하고, 실행기는 {@code kl.jobs.worker.enabled}일 때만 등록한다.
 *
 * <p>패키지 의존은 한 방향이다: domain ← application ← persistence ← worker.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProperties.class)
public class JobConfig {

    @Bean
    RetryPolicy jobRetryPolicy(JobProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.retryBase(), properties.retryMax(), new Random());
    }

    @Bean
    JobHandlers jobHandlers(ObjectProvider<JobHandler> handlers) {
        return new JobHandlers(handlers.orderedStream().toList());
    }

    @Bean
    JobMetrics jobMetrics(MeterRegistry meterRegistry) {
        return new JobMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.jobs.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    JobWorker jobWorker(JobEngine engine, JobHandlers handlers, JobMetrics metrics, JobProperties properties) {
        return new JobWorker(engine, handlers, metrics, properties, ownerId(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("job-scheduler").daemon().factory()),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("job-", 0).factory()));
    }

    /** 로그와 job.owner_id에서 실행기를 구분하는 값. 재시작하면 바뀐다. */
    static String ownerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }
}
