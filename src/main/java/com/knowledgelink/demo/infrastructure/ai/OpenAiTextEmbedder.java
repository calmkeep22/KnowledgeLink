package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.TextEmbedder;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI Embeddings API adapter. 과금 여부가 불명확한 호출을 숨겨 반복하지 않도록 재시도하지 않는다. */
public final class OpenAiTextEmbedder implements TextEmbedder {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DemoAiProperties.OpenAi properties;
    private final SensitiveTextMasker masker = new SensitiveTextMasker();

    OpenAiTextEmbedder(HttpClient httpClient, ObjectMapper objectMapper, DemoAiProperties.OpenAi properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public float[] embed(String text) {
        properties.requireEmbeddingConfigured();
        HttpRequest httpRequest = HttpRequest.newBuilder(properties.embeddingsEndpoint())
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildProviderRequest(text))))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActivitySummaryGenerationException("AI 임베딩 호출이 중단되었습니다.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ActivitySummaryGenerationException("AI 임베딩 공급자에 연결하지 못했습니다.", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ActivitySummaryGenerationException("AI 임베딩 공급자가 요청을 완료하지 못했습니다.");
        }
        return parseProviderResponse(response.body());
    }

    @Override
    public String modelId() {
        return "openai:" + properties.embeddingModel();
    }

    ObjectNode buildProviderRequest(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.embeddingModel());
        root.put("input", masker.mask(text));
        return root;
    }

    float[] parseProviderResponse(String body) {
        try {
            JsonNode embedding = objectMapper.readTree(body).path("data").path(0).path("embedding");
            return EmbeddingVectors.toVector(embedding, "OpenAI");
        } catch (ActivitySummaryGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("AI 임베딩 응답을 읽지 못했습니다.", exception);
        }
    }
}
