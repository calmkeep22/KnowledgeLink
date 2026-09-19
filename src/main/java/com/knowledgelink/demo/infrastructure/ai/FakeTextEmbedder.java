package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.TextEmbedder;
import java.util.Locale;

/**
 * 네트워크 없이 결정적으로 동작하는 feature hashing 임베딩.
 * 단어와 글자 2-gram을 해시해 고정 차원 벡터로 만든다. 한국어 조사가 붙어도("결제가", "결제를") 겹치는 2-gram으로 가까워진다.
 * 의미 유사도가 아니라 어휘 겹침만 반영하므로 흐름 검증과 오프라인 발표용이다.
 */
public final class FakeTextEmbedder implements TextEmbedder {
    static final int DIMENSIONS = 512;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        if (normalized.isEmpty()) {
            return vector;
        }
        for (String token : normalized.split(" ")) {
            add(vector, "w:" + token);
            for (int i = 0; i + 2 <= token.length(); i++) {
                add(vector, "g:" + token.substring(i, i + 2));
            }
        }
        normalize(vector);
        return vector;
    }

    @Override
    public String modelId() {
        return "fake-ngram-v1";
    }

    private static void add(float[] vector, String feature) {
        int hash = mix(feature.hashCode());
        int index = Math.floorMod(hash, DIMENSIONS);
        vector[index] += (hash & 0x4000_0000) == 0 ? 1.0f : -1.0f;
    }

    private static int mix(int value) {
        int h = value * 0x9E37_79B9;
        return h ^ (h >>> 16);
    }

    private static void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
    }
}
