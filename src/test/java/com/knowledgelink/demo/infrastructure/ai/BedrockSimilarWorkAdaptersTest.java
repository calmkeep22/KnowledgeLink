package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.SimilarWorkRequest;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SimilarWorkMatch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BedrockSimilarWorkAdaptersTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DemoAiProperties.Bedrock properties = new DemoAiProperties.Bedrock(
            "ap-northeast-2", "test-model", Duration.ofSeconds(1), "test-embedding-model");
    private final BedrockTextEmbedder embedder = new BedrockTextEmbedder(
            ignored -> embeddingResponse("{\"embedding\":[0.5,-0.25]}"), objectMapper, properties);
    private final BedrockSimilarWorkExplainer explainer = new BedrockSimilarWorkExplainer(
            ignored -> converseResponse("{}"), objectMapper, properties);

    @Test
    void 임베딩_요청은_Titan_V2_형식이고_입력을_마스킹한다() {
        var request = embedder.buildProviderRequest("담당자 dev@example.com, token=abc123");

        assertEquals("test-embedding-model", request.modelId());
        assertEquals("application/json", request.contentType());
        JsonNode body = objectMapper.readTree(request.body().asUtf8String());
        assertEquals(BedrockTextEmbedder.DIMENSIONS, body.path("dimensions").asInt());
        assertTrue(body.path("normalize").asBoolean());
        assertTrue(body.path("inputText").asText().contains("[MASKED_EMAIL]"));
        assertFalse(body.path("inputText").asText().contains("abc123"));
    }

    @Test
    void 임베딩_응답을_벡터로_읽고_비정상_응답은_거절한다() {
        assertArrayEquals(new float[] {0.5f, -0.25f}, embedder.embed("질의"));
        assertEquals("bedrock:test-embedding-model", embedder.modelId());

        assertThrows(ActivitySummaryGenerationException.class,
                () -> embedder.parseProviderResponse(embeddingResponse("{\"embedding\":[]}")));
        assertThrows(ActivitySummaryGenerationException.class,
                () -> embedder.parseProviderResponse(embeddingResponse("{\"embedding\":[\"x\"]}")));
        assertThrows(ActivitySummaryGenerationException.class,
                () -> embedder.parseProviderResponse(embeddingResponse("not json")));
    }

    @Test
    void 임베딩_모델이_없으면_호출하지_않는다() {
        BedrockTextEmbedder unconfigured = new BedrockTextEmbedder(
                ignored -> { throw new AssertionError("must not call"); },
                objectMapper,
                new DemoAiProperties.Bedrock("ap-northeast-2", "test-model", Duration.ofSeconds(1), ""));

        assertThrows(IllegalStateException.class, () -> unconfigured.embed("질의"));
    }

    @Test
    void 설명_요청은_지시와_마스킹된_검색_결과를_분리하고_팀원_이름을_보내지_않는다() {
        var providerRequest = explainer.buildProviderRequest(request());

        assertEquals("test-model", providerRequest.modelId());
        assertTrue(providerRequest.system().getFirst().text().contains("사람을 평가하거나 순위를 매기지 않는다"));
        String payload = providerRequest.messages().getFirst().content().getFirst().text();
        assertTrue(payload.contains("UNTRUSTED_MASKED_ACTIVITY_DATA"));
        assertTrue(payload.contains("[MASKED_EMAIL]"));
        assertTrue(payload.contains("\"similarity\":0.87"));
        assertFalse(payload.contains("개발자 A"));
        assertFalse(payload.contains("member-1"));
        assertFalse(payload.contains("source.example"));
    }

    @Test
    void 코드_펜스로_감싼_설명도_읽는다() {
        String output = """
                ```json
                {"overview":"결제 중복 사례가 있습니다.",
                "similarWork":[{"text":"같은 증상입니다.","evidenceIds":["P-1"]}],
                "suggestedApproach":[{"text":"멱등성 키를 참고하세요.","evidenceIds":["P-1"]}]}
                ```
                """;

        var explanation = explainer.parseProviderResponse(converseResponse(output), request());

        assertEquals("결제 중복 사례가 있습니다.", explanation.overview());
        assertEquals(List.of("P-1"), explanation.suggestedApproach().getFirst().evidenceIds());
        assertEquals("bedrock:test-model", explanation.generatedBy());
    }

    @Test
    void 검색_결과에_없는_근거는_거절한다() {
        String output = """
                {"overview":"요약","similarWork":[{"text":"비슷함","evidenceIds":["UNKNOWN"]}],"suggestedApproach":[]}
                """;

        assertThrows(ActivitySummaryGenerationException.class,
                () -> explainer.parseProviderResponse(converseResponse(output), request()));
    }

    private static InvokeModelResponse embeddingResponse(String body) {
        return InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(body)).build();
    }

    private static ConverseResponse converseResponse(String text) {
        Message message = Message.builder().content(ContentBlock.fromText(text)).build();
        return ConverseResponse.builder().output(ConverseOutput.builder().message(message).build()).build();
    }

    static SimilarWorkRequest request() {
        DemoActivity activity = new DemoActivity(
                "P-1", "project-1", "member-1", "개발자 A", ActivityKind.JIRA_ISSUE,
                "결제 중복 생성", "DONE", Instant.parse("2026-01-01T00:00:00Z"),
                "https://source.example/jira/1", "담당자 연락처 dev@example.com");
        return new SimilarWorkRequest("결제가 두 번 됩니다", List.of(new SimilarWorkMatch(activity, 0.87)));
    }
}
