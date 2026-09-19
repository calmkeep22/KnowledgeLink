package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiSimilarWorkAdaptersTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DemoAiProperties.OpenAi properties = new DemoAiProperties.OpenAi(
            "test-key-not-sent",
            "test-model",
            URI.create("https://example.invalid/v1/responses"),
            Duration.ofSeconds(1),
            "test-embedding-model",
            URI.create("https://example.invalid/v1/embeddings"));
    private final OpenAiTextEmbedder embedder =
            new OpenAiTextEmbedder(HttpClient.newHttpClient(), objectMapper, properties);
    private final OpenAiSimilarWorkExplainer explainer =
            new OpenAiSimilarWorkExplainer(HttpClient.newHttpClient(), objectMapper, properties);

    @Test
    void 임베딩_요청은_모델과_마스킹된_입력만_보낸다() {
        JsonNode request = embedder.buildProviderRequest("연락처 010-1234-5678");

        assertEquals("test-embedding-model", request.path("model").asText());
        assertEquals("연락처 [MASKED_PHONE]", request.path("input").asText());
        assertEquals("openai:test-embedding-model", embedder.modelId());
    }

    @Test
    void 임베딩_응답을_읽고_빈_응답은_거절한다() {
        assertArrayEquals(new float[] {0.1f, 0.2f},
                embedder.parseProviderResponse("{\"data\":[{\"embedding\":[0.1,0.2]}]}"));
        assertThrows(ActivitySummaryGenerationException.class,
                () -> embedder.parseProviderResponse("{\"data\":[]}"));
    }

    @Test
    void 설명_요청은_strict_schema로_출력_구조를_강제한다() {
        JsonNode request = explainer.buildProviderRequest(BedrockSimilarWorkAdaptersTest.request());

        assertFalse(request.path("store").asBoolean());
        assertEquals("developer", request.path("input").get(0).path("role").asText());
        String untrusted = request.path("input").get(1).path("content").asText();
        assertTrue(untrusted.contains("[MASKED_EMAIL]"));
        assertFalse(untrusted.contains("개발자 A"));
        JsonNode schema = request.path("text").path("format").path("schema");
        assertTrue(request.path("text").path("format").path("strict").asBoolean());
        assertEquals(3, schema.path("required").size());
        assertFalse(schema.path("properties").path("suggestedApproach").path("items")
                .path("additionalProperties").asBoolean());
    }

    @Test
    void 완료된_응답만_읽고_검색_결과_밖의_근거는_거절한다() {
        String valid = """
                {"overview":"비슷한 사례가 있습니다.","similarWork":[{"text":"같은 증상","evidenceIds":["P-1"]}],
                "suggestedApproach":[]}
                """;
        String unknownEvidence = """
                {"overview":"요약","similarWork":[{"text":"비슷함","evidenceIds":["UNKNOWN"]}],"suggestedApproach":[]}
                """;

        var explanation = explainer.parseProviderResponse(completed(valid), BedrockSimilarWorkAdaptersTest.request());

        assertEquals("openai:test-model", explanation.generatedBy());
        assertThrows(ActivitySummaryGenerationException.class,
                () -> explainer.parseProviderResponse(completed(unknownEvidence), BedrockSimilarWorkAdaptersTest.request()));
        assertThrows(ActivitySummaryGenerationException.class,
                () -> explainer.parseProviderResponse("{\"status\":\"incomplete\"}", BedrockSimilarWorkAdaptersTest.request()));
    }

    private String completed(String outputText) {
        return """
                {"status":"completed","output":[{"content":[{"type":"output_text","text":%s}]}]}
                """.formatted(objectMapper.writeValueAsString(outputText));
    }
}
