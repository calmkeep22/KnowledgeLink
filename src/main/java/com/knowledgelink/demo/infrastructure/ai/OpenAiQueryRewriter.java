package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.QueryRewriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI Responses API 기반 검색어 확장기. 재시도하지 않는다. */
public final class OpenAiQueryRewriter implements QueryRewriter {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DemoAiProperties.OpenAi properties;
    private final SensitiveTextMasker masker = new SensitiveTextMasker();

    OpenAiQueryRewriter(HttpClient httpClient, ObjectMapper objectMapper, DemoAiProperties.OpenAi properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String rewrite(String query) {
        properties.requireConfigured();
        HttpRequest httpRequest = HttpRequest.newBuilder(properties.endpoint())
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildProviderRequest(query))))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActivitySummaryGenerationException("검색어 확장 호출이 중단되었습니다.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ActivitySummaryGenerationException("검색어 확장 공급자에 연결하지 못했습니다.", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ActivitySummaryGenerationException("검색어 확장 공급자가 요청을 완료하지 못했습니다.");
        }
        return parseProviderResponse(response.body());
    }

    ObjectNode buildProviderRequest(String query) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.model());
        root.put("store", false);
        root.put("max_output_tokens", SearchQueryPrompt.MAX_OUTPUT_TOKENS);
        ArrayNode input = root.putArray("input");
        input.addObject().put("role", "developer").put("content", SearchQueryPrompt.INSTRUCTIONS);
        input.addObject().put("role", "user").put("content", masker.mask(query));
        return root;
    }

    String parseProviderResponse(String body) {
        JsonNode response;
        try {
            response = objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("검색어 확장 응답을 읽지 못했습니다.", exception);
        }
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    return SearchQueryPrompt.clean(content.path("text").asText(""));
                }
            }
        }
        throw new ActivitySummaryGenerationException("검색어 확장 공급자가 텍스트를 반환하지 않았습니다.");
    }
}
