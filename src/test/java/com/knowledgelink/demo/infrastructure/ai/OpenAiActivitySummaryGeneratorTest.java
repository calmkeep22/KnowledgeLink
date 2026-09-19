package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.SummaryGenerationRequest;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiActivitySummaryGeneratorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiActivitySummaryGenerator generator = new OpenAiActivitySummaryGenerator(
            HttpClient.newHttpClient(),
            objectMapper,
            new DemoAiProperties.OpenAi(
                    "test-key-not-sent",
                    "test-model",
                    URI.create("https://example.invalid/v1/responses"),
                    Duration.ofSeconds(1)));

    @Test
    void 공급자_요청은_지시와_마스킹된_자료를_분리하고_strict_schema를_사용한다() {
        JsonNode request = generator.buildProviderRequest(request());

        assertFalse(request.path("store").asBoolean());
        assertEquals(2000, request.path("max_output_tokens").asInt());
        assertEquals("developer", request.path("input").get(0).path("role").asText());
        assertEquals("user", request.path("input").get(1).path("role").asText());
        String untrustedData = request.path("input").get(1).path("content").asText();
        assertTrue(untrustedData.contains("UNTRUSTED_MASKED_ACTIVITY_DATA"));
        assertTrue(untrustedData.contains("[MASKED_EMAIL]"));
        assertTrue(untrustedData.contains("[MASKED_SECRET]"));
        assertFalse(untrustedData.contains("개발자 A"));
        assertFalse(untrustedData.contains("source.example"));

        JsonNode schema = request.path("text").path("format").path("schema");
        assertTrue(request.path("text").path("format").path("strict").asBoolean());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(5, schema.path("required").size());
        assertFalse(schema.path("properties").path("completed").path("items")
                .path("additionalProperties").asBoolean());
    }

    @Test
    void 응답의_근거_ID가_이번_요청에_없으면_거절한다() {
        String providerOutput = """
                {"title":"요약","completed":[{"text":"완료","evidenceIds":["UNKNOWN"]}],
                "inProgress":[],"blockers":[],"nextActions":[]}
                """;
        String response = completedResponse(providerOutput);

        assertThrows(ActivitySummaryGenerationException.class,
                () -> generator.parseProviderResponse(response, request()));
    }

    @Test
    void 완료된_구조화_응답을_공통_계약으로_변환한다() {
        String providerOutput = """
                {"title":"이번 주 요약","completed":[{"text":"PR을 병합했습니다.","evidenceIds":["ACT-1"]}],
                "inProgress":[],"blockers":[],"nextActions":[]}
                """;

        var summary = generator.parseProviderResponse(completedResponse(providerOutput), request());

        assertEquals("이번 주 요약", summary.title());
        assertEquals(List.of("ACT-1"), summary.completed().getFirst().evidenceIds());
        assertEquals("openai:test-model", summary.generatedBy());
    }

    private String completedResponse(String outputText) {
        return """
                {"status":"completed","output":[{"content":[{"type":"output_text","text":%s}]}]}
                """.formatted(objectMapper.writeValueAsString(outputText));
    }

    private static SummaryGenerationRequest request() {
        DemoActivity activity = new DemoActivity(
                "ACT-1",
                "project-1",
                "member-1",
                "개발자 A",
                ActivityKind.GITHUB_PULL_REQUEST,
                "로그인 수정 담당자는 dev@example.com",
                "MERGED",
                Instant.parse("2026-09-18T09:00:00Z"),
                "https://source.example/pr/1",
                "token=very-secret-value");
        return new SummaryGenerationRequest(SummaryMode.MEMBER, "member-1", "개발자 A", List.of(activity));
    }
}
