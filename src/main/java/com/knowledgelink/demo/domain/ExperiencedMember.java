package com.knowledgelink.demo.domain;

import java.util.List;

/** 검색된 과거 업무를 맡았던 팀원. 개인 평가가 아니라 이번 질의와 관련된 경험의 근거만 담는다. */
public record ExperiencedMember(String memberId, String memberName, double relevance, List<String> evidenceIds) {
    public ExperiencedMember {
        if (memberId == null || memberId.isBlank() || memberName == null || memberName.isBlank()) {
            throw new IllegalArgumentException("팀원 식별 정보가 필요합니다.");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("경험 근거가 하나 이상 필요합니다.");
        }
    }
}
