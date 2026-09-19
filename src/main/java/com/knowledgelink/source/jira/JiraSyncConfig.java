package com.knowledgelink.source.jira;

import com.knowledgelink.job.application.JobHandler;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code kl.jira.enabled=true}일 때만 Jira Cloud adapter와 SYNC handler를 함께 등록한다.
 * 둘을 같은 조건으로 묶어, bean 등록 순서에 따라 handler만 빠지는 일이 없게 한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kl.jira", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(JiraProperties.class)
public class JiraSyncConfig {

    @Bean
    JiraIssueSource jiraIssueSource(JiraProperties properties, ObjectMapper objectMapper, Environment environment,
                                    Clock clock) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new JiraCloudIssueSource(client, objectMapper, properties, environment::getProperty, clock);
    }

    @Bean
    JobHandler jiraSyncJobHandler(JiraIssueSource source, JiraSyncStore store) {
        return new JiraSyncHandler(source, store);
    }
}
