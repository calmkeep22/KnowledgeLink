package com.knowledgelink.demo.domain;

import java.time.Instant;
import java.util.List;

/** 유사 과거 업무 검색 응답 계약. */
public record SimilarWorkResult(
        String schemaVersion,
        String query,
        List<SimilarWorkMatch> matches,
        List<ExperiencedMember> experiencedMembers,
        SimilarWorkExplanation explanation,
        String embeddingModel,
        Instant generatedAt
) {
    public SimilarWorkResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "demo-similar-work-v1" : schemaVersion;
        matches = matches == null ? List.of() : List.copyOf(matches);
        experiencedMembers = experiencedMembers == null ? List.of() : List.copyOf(experiencedMembers);
    }
}
