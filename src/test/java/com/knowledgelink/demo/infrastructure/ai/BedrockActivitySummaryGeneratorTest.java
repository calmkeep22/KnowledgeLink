package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.SummaryGenerationRequest;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import tools.jackson.databind.ObjectMapper;

class BedrockActivitySummaryGeneratorTest {
    private final BedrockActivitySummaryGenerator generator = new BedrockActivitySummaryGenerator(
            ignored -> response("{}"),
            new ObjectMapper(),
            new DemoAiProperties.Bedrock("ap-northeast-2", "test-model", Duration.ofSeconds(1), "test-embedding-model"));

    @Test
    void 요청은_시스템_지시와_마스킹된_자료를_분리한다() {
        var providerRequest = generator.buildProviderRequest(request());

        assertEquals("test-model", providerRequest.modelId());
        assertTrue(providerRequest.system().getFirst().text().contains("사람을 평가하거나 순위를 매기지 않는다"));
        String payload = providerRequest.messages().getFirst().content().getFirst().text();
        assertTrue(payload.contains("UNTRUSTED_MASKED_ACTIVITY_DATA"));
        assertTrue(payload.contains("[MASKED_EMAIL]"));
        assertTrue(payload.contains("[MASKED_SECRET]"));
        assertFalse(payload.contains("개발자 A"));
        assertFalse(payload.contains("source.example"));
        assertEquals(2000, providerRequest.inferenceConfig().maxTokens());
    }

    @Test
    void 허용되지_않은_근거_ID가_있으면_거절한다() {
        String output = """
                {"title":"요약","completed":[{"text":"완료","evidenceIds":["UNKNOWN"]}],
                "inProgress":[],"blockers":[],"nextActions":[]}
                """;

        assertThrows(ActivitySummaryGenerationException.class,
                () -> generator.parseProviderResponse(response(output), request()));
    }

    @Test
    void 구조화된_응답을_공통_계약으로_변환한다() {
        String output = """
                {"title":"이번 주 요약","completed":[{"text":"PR을 병합했습니다.","evidenceIds":["ACT-1"]}],
                "inProgress":[],"blockers":[],"nextActions":[]}
                """;

        var summary = generator.parseProviderResponse(response(output), request());

        assertEquals("이번 주 요약", summary.title());
        assertEquals(List.of("ACT-1"), summary.completed().getFirst().evidenceIds());
        assertEquals("bedrock:test-model", summary.generatedBy());
    }

    @Test
    void 코드_펜스로_감싼_응답도_파싱한다() {
        String output = """
                ```json
                {"title":"이번 주 요약","completed":[{"text":"PR을 병합했습니다.","evidenceIds":["ACT-1"]}],
                "inProgress":[],"blockers":[],"nextActions":[]}
                ```
                """;

        var summary = generator.parseProviderResponse(response(output), request());

        assertEquals("이번 주 요약", summary.title());
    }

    private static ConverseResponse response(String text) {
        Message message = Message.builder().content(ContentBlock.fromText(text)).build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .build();
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
