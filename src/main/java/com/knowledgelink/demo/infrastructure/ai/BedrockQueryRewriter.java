package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.QueryRewriter;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/** Amazon Bedrock Converse 기반 검색어 확장기. 요약·설명과 같은 생성 모델을 쓰고 재시도하지 않는다. */
public final class BedrockQueryRewriter implements QueryRewriter {
    private final BedrockActivitySummaryGenerator.ConverseClient client;
    private final DemoAiProperties.Bedrock properties;
    private final SensitiveTextMasker masker = new SensitiveTextMasker();

    BedrockQueryRewriter(BedrockActivitySummaryGenerator.ConverseClient client, DemoAiProperties.Bedrock properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String rewrite(String query) {
        properties.requireConfigured();
        ConverseResponse response;
        try {
            response = client.converse(buildProviderRequest(query));
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("Bedrock 검색어 확장에 실패했습니다.", exception);
        }
        return parseProviderResponse(response);
    }

    ConverseRequest buildProviderRequest(String query) {
        return ConverseRequest.builder()
                .modelId(properties.model())
                .system(SystemContentBlock.fromText(SearchQueryPrompt.INSTRUCTIONS))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(masker.mask(query)))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(SearchQueryPrompt.MAX_OUTPUT_TOKENS)
                        .temperature(0.0f)
                        .build())
                .build();
    }

    String parseProviderResponse(ConverseResponse response) {
        if (response == null || response.output() == null || response.output().message() == null) {
            throw new ActivitySummaryGenerationException("Bedrock이 검색어를 반환하지 않았습니다.");
        }
        String text = response.output().message().content().stream()
                .map(ContentBlock::text)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        return SearchQueryPrompt.clean(text);
    }
}
