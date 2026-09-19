package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.SimilarWorkExplainer;
import com.knowledgelink.demo.application.SimilarWorkRequest;
import com.knowledgelink.demo.domain.SimilarWorkExplanation;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import tools.jackson.databind.ObjectMapper;

/** Amazon Bedrock Converse 기반 유사 업무 설명기. 요약 adapter와 같은 모델·자격 증명을 쓰고 재시도하지 않는다. */
public final class BedrockSimilarWorkExplainer implements SimilarWorkExplainer {
    private final BedrockActivitySummaryGenerator.ConverseClient client;
    private final ObjectMapper objectMapper;
    private final DemoAiProperties.Bedrock properties;
    private final SensitiveTextMasker masker = new SensitiveTextMasker();

    BedrockSimilarWorkExplainer(
            BedrockActivitySummaryGenerator.ConverseClient client,
            ObjectMapper objectMapper,
            DemoAiProperties.Bedrock properties
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public SimilarWorkExplanation explain(SimilarWorkRequest request) {
        properties.requireConfigured();
        ConverseResponse response;
        try {
            response = client.converse(buildProviderRequest(request));
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("Bedrock 설명 공급자에 연결하지 못했습니다.", exception);
        }
        return parseProviderResponse(response, request);
    }

    ConverseRequest buildProviderRequest(SimilarWorkRequest request) {
        String payload = objectMapper.writeValueAsString(SimilarWorkPrompt.untrustedPayload(objectMapper, masker, request));
        return ConverseRequest.builder()
                .modelId(properties.model())
                .system(SystemContentBlock.fromText(SimilarWorkPrompt.INSTRUCTIONS + SimilarWorkPrompt.JSON_ONLY))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(payload))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(2000)
                        .temperature(0.0f)
                        .build())
                .build();
    }

    SimilarWorkExplanation parseProviderResponse(ConverseResponse response, SimilarWorkRequest request) {
        try {
            if (response == null || response.output() == null || response.output().message() == null) {
                throw new ActivitySummaryGenerationException("Bedrock이 설명 결과를 반환하지 않았습니다.");
            }
            String text = response.output().message().content().stream()
                    .map(ContentBlock::text)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElseThrow(() -> new ActivitySummaryGenerationException("Bedrock이 텍스트 결과를 반환하지 않았습니다."));
            SimilarWorkPrompt.ProviderExplanation provider = objectMapper.readValue(
                    BedrockActivitySummaryGenerator.stripCodeFence(text.strip()),
                    SimilarWorkPrompt.ProviderExplanation.class);
            return SimilarWorkPrompt.toExplanation(provider, request, "bedrock:" + properties.model());
        } catch (ActivitySummaryGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("Bedrock 설명 응답을 검증하지 못했습니다.", exception);
        }
    }
}
