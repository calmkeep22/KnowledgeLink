package com.knowledgelink.demo.domain;

import java.time.Instant;
import java.util.List;

/** fake/OpenAI adapter가 공통으로 반환하는 데모 요약 계약. */
public record WorkSummary(
        String schemaVersion,
        SummaryMode mode,
        String subjectId,
        String title,
        List<SummaryPoint> completed,
        List<SummaryPoint> inProgress,
        List<SummaryPoint> blockers,
        List<SummaryPoint> nextActions,
        Instant generatedAt,
        String generatedBy
) {
    public WorkSummary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "demo-summary-v1" : schemaVersion;
        if (mode == null || subjectId == null || subjectId.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("요약 식별 정보가 필요합니다.");
        }
        completed = immutable(completed);
        inProgress = immutable(inProgress);
        blockers = immutable(blockers);
        nextActions = immutable(nextActions);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        generatedBy = generatedBy == null || generatedBy.isBlank() ? "unknown" : generatedBy;
    }

    private static List<SummaryPoint> immutable(List<SummaryPoint> points) {
        return points == null ? List.of() : List.copyOf(points);
    }
}
