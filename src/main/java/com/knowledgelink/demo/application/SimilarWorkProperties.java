package com.knowledgelink.demo.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 유사 업무 검색 설정.
 *
 * @param topK 설명에 넘길 과거 업무 수
 * @param maxAiRequestsPerMinute 캐시되지 않은 질의가 공급자를 호출할 수 있는 분당 최대 횟수. 공개 데모의 비용 상한이다.
 * @param cacheSize 결과를 보관할 서로 다른 질의 수
 * @param minRelevantScore 1위 유사도가 이보다 낮으면 비슷한 사례가 없다고 보고 설명 모델을 부르지 않는다.
 *                         임베딩 모델마다 점수 분포가 달라 설정으로 맞춘다. 0이면 끈다.
 */
@ConfigurationProperties("kl.demo.similar-work")
public record SimilarWorkProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("60") int maxAiRequestsPerMinute,
        @DefaultValue("200") int cacheSize,
        @DefaultValue("0") double minRelevantScore
) {
    @ConstructorBinding
    public SimilarWorkProperties {
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK는 1 이상 20 이하여야 합니다.");
        }
        if (maxAiRequestsPerMinute < 1) {
            throw new IllegalArgumentException("maxAiRequestsPerMinute는 1 이상이어야 합니다.");
        }
        if (cacheSize < 1) {
            throw new IllegalArgumentException("cacheSize는 1 이상이어야 합니다.");
        }
        if (minRelevantScore < 0 || minRelevantScore >= 1) {
            throw new IllegalArgumentException("minRelevantScore는 0 이상 1 미만이어야 합니다.");
        }
    }

    public SimilarWorkProperties(int topK, int maxAiRequestsPerMinute, int cacheSize) {
        this(topK, maxAiRequestsPerMinute, cacheSize, 0);
    }
}
