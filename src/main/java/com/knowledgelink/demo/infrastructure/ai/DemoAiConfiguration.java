package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerator;
import com.knowledgelink.demo.application.QueryRewriter;
import com.knowledgelink.demo.application.SimilarWorkExplainer;
import com.knowledgelink.demo.application.SimilarWorkProperties;
import com.knowledgelink.demo.application.TextEmbedder;
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

/** application 출력 port에 데모용 AI adapter를 조립한다. 요약·임베딩·설명은 같은 공급자 설정을 따른다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
@EnableConfigurationProperties({DemoAiProperties.class, SimilarWorkProperties.class})
public class DemoAiConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "fake", matchIfMissing = true)
    ActivitySummaryGenerator fakeActivitySummaryGenerator() {
        return new FakeActivitySummaryGenerator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "fake", matchIfMissing = true)
    TextEmbedder fakeTextEmbedder() {
        return new FakeTextEmbedder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "fake", matchIfMissing = true)
    SimilarWorkExplainer fakeSimilarWorkExplainer() {
        return new FakeSimilarWorkExplainer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "fake", matchIfMissing = true)
    QueryRewriter fakeQueryRewriter() {
        return QueryRewriter.NONE;
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "openai")
    QueryRewriter openAiQueryRewriter(HttpClient openAiHttpClient, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new OpenAiQueryRewriter(openAiHttpClient, objectMapper, properties.openai());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "bedrock")
    QueryRewriter bedrockQueryRewriter(BedrockRuntimeClient client, DemoAiProperties properties) {
        return new BedrockQueryRewriter(client::converse, properties.bedrock());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "openai")
    HttpClient openAiHttpClient(DemoAiProperties properties) {
        properties.openai().requireConfigured();
        properties.openai().requireEmbeddingConfigured();
        return HttpClient.newBuilder()
                .connectTimeout(properties.openai().timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "openai")
    ActivitySummaryGenerator openAiActivitySummaryGenerator(
            HttpClient openAiHttpClient, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new OpenAiActivitySummaryGenerator(openAiHttpClient, objectMapper, properties.openai());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "openai")
    TextEmbedder openAiTextEmbedder(HttpClient openAiHttpClient, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new OpenAiTextEmbedder(openAiHttpClient, objectMapper, properties.openai());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "openai")
    SimilarWorkExplainer openAiSimilarWorkExplainer(
            HttpClient openAiHttpClient, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new OpenAiSimilarWorkExplainer(openAiHttpClient, objectMapper, properties.openai());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "bedrock")
    BedrockRuntimeClient bedrockRuntimeClient(DemoAiProperties properties) {
        properties.bedrock().requireConfigured();
        properties.bedrock().requireEmbeddingConfigured();
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
            BedrockRuntimeClient client, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new BedrockActivitySummaryGenerator(client::converse, objectMapper, properties.bedrock());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "bedrock")
    TextEmbedder bedrockTextEmbedder(BedrockRuntimeClient client, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new BedrockTextEmbedder(client::invokeModel, objectMapper, properties.bedrock());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kl.demo.ai", name = "provider", havingValue = "bedrock")
    SimilarWorkExplainer bedrockSimilarWorkExplainer(
            BedrockRuntimeClient client, DemoAiProperties properties, ObjectMapper objectMapper) {
        return new BedrockSimilarWorkExplainer(client::converse, objectMapper, properties.bedrock());
    }
}
