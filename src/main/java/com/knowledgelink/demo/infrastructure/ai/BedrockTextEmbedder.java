package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.TextEmbedder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Amazon Titan Text Embeddings V2 adapter(InvokeModel). 요청 형식은 Titan V2 기준이며 다른 임베딩 모델은 지원하지 않는다.
 * SDK 기본 자격 증명 체인을 사용하고 호출을 숨겨 재시도하지 않는다.
 */
public final class BedrockTextEmbedder implements TextEmbedder {
    static final int DIMENSIONS = 512;

    @FunctionalInterface
    interface InvokeClient {
        InvokeModelResponse invokeModel(InvokeModelRequest request);
    }

    private final InvokeClient client;
    private final ObjectMapper objectMapper;
    private final DemoAiProperties.Bedrock properties;
    private final SensitiveTextMasker masker = new SensitiveTextMasker();

    BedrockTextEmbedder(InvokeClient client, ObjectMapper objectMapper, DemoAiProperties.Bedrock properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public float[] embed(String text) {
        properties.requireEmbeddingConfigured();
        InvokeModelResponse response;
        try {
            response = client.invokeModel(buildProviderRequest(text));
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("Bedrock 임베딩 공급자에 연결하지 못했습니다.", exception);
        }
        return parseProviderResponse(response);
    }

    @Override
    public String modelId() {
        return "bedrock:" + properties.embeddingModel();
    }

    InvokeModelRequest buildProviderRequest(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("inputText", masker.mask(text));
        body.put("dimensions", DIMENSIONS);
        body.put("normalize", true);
        return InvokeModelRequest.builder()
                .modelId(properties.embeddingModel())
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(body)))
                .build();
    }

    float[] parseProviderResponse(InvokeModelResponse response) {
        try {
            if (response == null || response.body() == null) {
                throw new ActivitySummaryGenerationException("Bedrock이 임베딩을 반환하지 않았습니다.");
            }
            JsonNode embedding = objectMapper.readTree(response.body().asUtf8String()).path("embedding");
            return EmbeddingVectors.toVector(embedding, "Bedrock");
        } catch (ActivitySummaryGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("Bedrock 임베딩 응답을 읽지 못했습니다.", exception);
        }
    }
}
