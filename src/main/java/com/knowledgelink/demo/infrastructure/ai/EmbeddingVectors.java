package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerationException;
import tools.jackson.databind.JsonNode;

final class EmbeddingVectors {
    private EmbeddingVectors() {
    }

    /** 공급자 JSON 숫자 배열을 벡터로 바꾼다. 비었거나 숫자가 아닌 값이 있으면 거절한다. */
    static float[] toVector(JsonNode array, String provider) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new ActivitySummaryGenerationException(provider + "이 임베딩 벡터를 반환하지 않았습니다.");
        }
        float[] vector = new float[array.size()];
        for (int i = 0; i < vector.length; i++) {
            JsonNode value = array.get(i);
            if (!value.isNumber()) {
                throw new ActivitySummaryGenerationException(provider + " 임베딩에 숫자가 아닌 값이 있습니다.");
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }
}
