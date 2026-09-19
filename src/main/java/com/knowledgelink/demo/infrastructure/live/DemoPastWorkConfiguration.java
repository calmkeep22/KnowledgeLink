package com.knowledgelink.demo.infrastructure.live;

import com.knowledgelink.demo.application.PastWorkSource;
import com.knowledgelink.demo.infrastructure.MockPastWorkSource;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 과거 업무 출처를 설정으로 고른다. 기본은 네트워크 없이 동작하는 가명 fixture다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
@EnableConfigurationProperties(LivePastWorkProperties.class)
public class DemoPastWorkConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.past-work", name = "source", havingValue = "mock", matchIfMissing = true)
    PastWorkSource mockPastWorkSource(LivePastWorkProperties properties, ObjectMapper objectMapper) {
        return new MockPastWorkSource(objectMapper, properties.mockExamples());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.past-work", name = "source", havingValue = "apache")
    PastWorkSource livePastWorkSource(LivePastWorkProperties properties, ObjectMapper objectMapper) {
        LivePastWorkProperties.Apache apache = properties.apache();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(apache.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpGetter http = HttpGetter.of(client, apache.timeout());
        return new LivePastWorkSource(
                new GitHubPullRequestClient(http, objectMapper, apache.githubRepository(), apache.githubToken()),
                new ApacheJiraClient(http, objectMapper, apache.jiraBase()),
                apache,
                objectMapper,
                new MockPastWorkSource(objectMapper, properties.mockExamples()),
                Clock.systemUTC());
    }
}
