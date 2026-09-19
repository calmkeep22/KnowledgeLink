package com.knowledgelink.demo.domain;

import java.util.List;

/** 문장마다 입력 활동 근거를 강제한다. */
public record SummaryPoint(String text, List<String> evidenceIds) {
    public SummaryPoint {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("요약 문장은 비어 있을 수 없습니다.");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("요약 문장에는 근거가 하나 이상 필요합니다.");
        }
    }
}
