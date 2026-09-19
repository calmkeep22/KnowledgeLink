package com.knowledgelink.demo.domain;

import java.util.List;

/** 검색 결과만 근거로 만든 설명. 항목마다 과거 업무 evidence ID를 강제한다. */
public record SimilarWorkExplanation(
        String overview,
        List<SummaryPoint> similarWork,
        List<SummaryPoint> suggestedApproach,
        String generatedBy
) {
    public SimilarWorkExplanation {
        if (overview == null || overview.isBlank()) {
            throw new IllegalArgumentException("설명 요약이 필요합니다.");
        }
        similarWork = similarWork == null ? List.of() : List.copyOf(similarWork);
        suggestedApproach = suggestedApproach == null ? List.of() : List.copyOf(suggestedApproach);
        generatedBy = generatedBy == null || generatedBy.isBlank() ? "unknown" : generatedBy;
    }
}
