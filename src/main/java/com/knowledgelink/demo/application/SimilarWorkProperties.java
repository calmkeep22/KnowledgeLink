package com.knowledgelink.demo.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 유사 업무 검색 설정.
 *
 * @param topK 설명에 넘길 과거 업무 수
 * @param maxAiRequestsPerMinute 캐시되지 않은 질의가 공급자를 호출할 수 있는 분당 최대 횟수. 공개 데모의 비용 상한이다.
 * @param cacheSize 결과를 보관할 서로 다른 질의 수
 */
@ConfigurationProperties("kl.demo.similar-work")
public record SimilarWorkProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("20") int maxAiRequestsPerMinute,
        @DefaultValue("200") int cacheSize
) {
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
    }
}
