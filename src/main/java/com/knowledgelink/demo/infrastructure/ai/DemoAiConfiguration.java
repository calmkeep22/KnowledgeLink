package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerator;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** application 출력 port에 데모용 AI adapter를 조립한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
@EnableConfigurationProperties(DemoAiProperties.class)
public class DemoAiConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "fake", matchIfMissing = true)
    ActivitySummaryGenerator fakeActivitySummaryGenerator() {
        return new FakeActivitySummaryGenerator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "openai")
    ActivitySummaryGenerator openAiActivitySummaryGenerator(DemoAiProperties properties, ObjectMapper objectMapper) {
        properties.openai().requireConfigured();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.openai().timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new OpenAiActivitySummaryGenerator(client, objectMapper, properties.openai());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "bedrock")
    BedrockRuntimeClient bedrockRuntimeClient(DemoAiProperties properties) {
        properties.bedrock().requireConfigured();
        return BedrockRuntimeClient.builder()
                .region(Region.of(properties.bedrock().region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(properties.bedrock().timeout())
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "bedrock")
    ActivitySummaryGenerator bedrockActivitySummaryGenerator(
            BedrockRuntimeClient client,
            DemoAiProperties properties,
            ObjectMapper objectMapper
    ) {
        return new BedrockActivitySummaryGenerator(client::converse, objectMapper, properties.bedrock());
    }
}
