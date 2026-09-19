package com.knowledgelink.demo.domain;

import java.util.Objects;

/** 새 업무와 비교한 과거 업무 한 건과 코사인 유사도. */
public record SimilarWorkMatch(DemoActivity activity, double score) {
    public SimilarWorkMatch {
        Objects.requireNonNull(activity, "activity");
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("유사도는 숫자여야 합니다.");
        }
    }
}
