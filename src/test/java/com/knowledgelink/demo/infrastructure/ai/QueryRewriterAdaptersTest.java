package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class QueryRewriterAdaptersTest {

    @Test
    void 응답의_첫_줄만_쓰고_따옴표와_코드_표시를_걷어_낸다() {
        assertEquals("consumer high CPU after failed reauthentication",
                SearchQueryPrompt.clean("```\n\"consumer high CPU after failed reauthentication\"\nextra line"));
        assertEquals("a b", SearchQueryPrompt.clean("  a   b  "));
        assertEquals(300, SearchQueryPrompt.clean("x".repeat(400)).length());
        assertThrows(ActivitySummaryGenerationException.class, () -> SearchQueryPrompt.clean("  \n```\n"));
        assertThrows(ActivitySummaryGenerationException.class, () -> SearchQueryPrompt.clean(null));
    }

    @Test
    void Bedrock_요청은_지시와_마스킹된_질문을_분리하고_짧은_출력만_허용한다() {
        BedrockQueryRewriter rewriter = new BedrockQueryRewriter(
                request -> converse("RocksDB native memory leak OOM on store close"),
                new DemoAiProperties.Bedrock("ap-northeast-2", "test-model", Duration.ofSeconds(1), "embed"));

        var request = rewriter.buildProviderRequest("dev@example.com 에서 메모리가 새요");

        assertEquals("test-model", request.modelId());
        assertTrue(request.system().getFirst().text().contains("untrusted data"));
        assertEquals("[MASKED_EMAIL] 에서 메모리가 새요", request.messages().getFirst().content().getFirst().text());
        assertEquals(SearchQueryPrompt.MAX_OUTPUT_TOKENS, request.inferenceConfig().maxTokens());
        assertEquals("RocksDB native memory leak OOM on store close", rewriter.rewrite("메모리가 새요"));
    }

    @Test
    void Bedrock_호출_실패는_공급자_실패로_감싼다() {
        BedrockQueryRewriter rewriter = new BedrockQueryRewriter(
                request -> { throw new IllegalStateException("throttled"); },
                new DemoAiProperties.Bedrock("ap-northeast-2", "test-model", Duration.ofSeconds(1), "embed"));

        assertThrows(ActivitySummaryGenerationException.class, () -> rewriter.rewrite("질문"));
    }

    @Test
    void OpenAI_요청과_응답() {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiQueryRewriter rewriter = new OpenAiQueryRewriter(HttpClient.newHttpClient(), objectMapper,
                new DemoAiProperties.OpenAi("key", "test-model", URI.create("https://example.invalid/v1/responses"),
                        Duration.ofSeconds(1), "embed", URI.create("https://example.invalid/v1/embeddings")));

        JsonNode request = rewriter.buildProviderRequest("토큰 token=abc 가 로그에 찍혀요");

        assertFalse(request.path("store").asBoolean());
        assertEquals("developer", request.path("input").get(0).path("role").asText());
        assertFalse(request.path("input").get(1).path("content").asText().contains("abc"));
        assertEquals("secrets logged in plain text",
                rewriter.parseProviderResponse("""
                        {"output":[{"content":[{"type":"output_text","text":"secrets logged in plain text"}]}]}
                        """));
        assertThrows(ActivitySummaryGenerationException.class, () -> rewriter.parseProviderResponse("{\"output\":[]}"));
    }

    private static ConverseResponse converse(String text) {
        return ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder().content(ContentBlock.fromText(text)).build())
                        .build())
                .build();
    }
}
