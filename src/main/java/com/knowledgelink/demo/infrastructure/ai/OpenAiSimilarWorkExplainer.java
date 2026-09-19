package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import com.knowledgelink.demo.application.SimilarWorkExplainer;
import com.knowledgelink.demo.application.SimilarWorkRequest;
import com.knowledgelink.demo.domain.SimilarWorkExplanation;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI Responses API 기반 유사 업무 설명기. strict JSON schema로 출력 구조를 강제하고 재시도하지 않는다. */
public final class OpenAiSimilarWorkExplainer implements SimilarWorkExplainer {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DemoAiProperties.OpenAi properties;
    private final SensitiveTextMasker masker = new SensitiveTextMasker();

    OpenAiSimilarWorkExplainer(HttpClient httpClient, ObjectMapper objectMapper, DemoAiProperties.OpenAi properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public SimilarWorkExplanation explain(SimilarWorkRequest request) {
        properties.requireConfigured();
        HttpRequest httpRequest = HttpRequest.newBuilder(properties.endpoint())
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildProviderRequest(request))))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActivitySummaryGenerationException("AI 설명 호출이 중단되었습니다.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ActivitySummaryGenerationException("AI 설명 공급자에 연결하지 못했습니다.", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ActivitySummaryGenerationException("AI 설명 공급자가 요청을 완료하지 못했습니다.");
        }
        return parseProviderResponse(response.body(), request);
    }

    ObjectNode buildProviderRequest(SimilarWorkRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.model());
        root.put("store", false);
        root.put("max_output_tokens", 2000);
        ArrayNode input = root.putArray("input");
        input.add(message("developer", SimilarWorkPrompt.INSTRUCTIONS));
        input.add(message("user", objectMapper.writeValueAsString(
                SimilarWorkPrompt.untrustedPayload(objectMapper, masker, request))));

        ObjectNode format = root.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", "similar_work_explanation");
        format.put("strict", true);
        format.set("schema", outputSchema());
        return root;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private ObjectNode outputSchema() {
        ObjectNode point = objectMapper.createObjectNode();
        point.put("type", "object");
        point.put("additionalProperties", false);
        point.putArray("required").add("text").add("evidenceIds");
        ObjectNode pointProperties = point.putObject("properties");
        pointProperties.putObject("text").put("type", "string");
        ObjectNode evidenceIds = pointProperties.putObject("evidenceIds");
        evidenceIds.put("type", "array");
        evidenceIds.putObject("items").put("type", "string");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("overview").add("similarWork").add("suggestedApproach");
        ObjectNode propertiesNode = schema.putObject("properties");
        propertiesNode.putObject("overview").put("type", "string");
        for (String name : new String[] {"similarWork", "suggestedApproach"}) {
            ObjectNode array = propertiesNode.putObject(name);
            array.put("type", "array");
            array.set("items", point.deepCopy());
        }
        return schema;
    }

    SimilarWorkExplanation parseProviderResponse(String body, SimilarWorkRequest request) {
        try {
            JsonNode response = objectMapper.readTree(body);
            if (!"completed".equals(response.path("status").asText())) {
                throw new ActivitySummaryGenerationException("AI 설명 공급자가 완전한 결과를 반환하지 않았습니다.");
            }
            SimilarWorkPrompt.ProviderExplanation provider = objectMapper.readValue(
                    extractOutputText(response), SimilarWorkPrompt.ProviderExplanation.class);
            return SimilarWorkPrompt.toExplanation(provider, request, "openai:" + properties.model());
        } catch (ActivitySummaryGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ActivitySummaryGenerationException("AI 설명 공급자의 응답을 검증하지 못했습니다.", exception);
        }
    }

    private static String extractOutputText(JsonNode response) {
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText()) && !content.path("text").asText().isBlank()) {
                    return content.path("text").asText();
                }
            }
        }
        throw new ActivitySummaryGenerationException("AI 설명 공급자가 구조화된 결과를 반환하지 않았습니다.");
    }
}
