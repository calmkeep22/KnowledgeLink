package com.knowledgelink.demo.application;

import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import java.util.List;

public record SummaryGenerationRequest(
        SummaryMode mode,
        String subjectId,
        String subjectName,
        List<DemoActivity> activities
) {
    public SummaryGenerationRequest {
        if (mode == null || subjectId == null || subjectId.isBlank()
                || subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("요약 요청 식별 정보가 필요합니다.");
        }
        activities = activities == null ? List.of() : List.copyOf(activities);
        if (activities.isEmpty()) {
            throw new IllegalArgumentException("요약할 활동이 없습니다.");
        }
    }
}
