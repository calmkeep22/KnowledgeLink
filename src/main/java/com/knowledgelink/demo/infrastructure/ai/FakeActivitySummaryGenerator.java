package com.knowledgelink.demo.infrastructure.ai;

import com.knowledgelink.demo.application.ActivitySummaryGenerator;
import com.knowledgelink.demo.application.SummaryGenerationRequest;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryPoint;
import com.knowledgelink.demo.domain.WorkSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 네트워크와 비용 없이 end-to-end 흐름을 검증하는 결정적 adapter. */
public final class FakeActivitySummaryGenerator implements ActivitySummaryGenerator {
    @Override
    public WorkSummary generate(SummaryGenerationRequest request) {
        List<DemoActivity> activities = request.activities().stream()
                .sorted(Comparator.comparing(DemoActivity::occurredAt).thenComparing(DemoActivity::id))
                .toList();
        List<SummaryPoint> completed = new ArrayList<>();
        List<SummaryPoint> inProgress = new ArrayList<>();
        List<SummaryPoint> blockers = new ArrayList<>();
        List<SummaryPoint> nextActions = new ArrayList<>();

        for (DemoActivity activity : activities) {
            String status = activity.status().toLowerCase(Locale.ROOT);
            SummaryPoint point = point(activity, activity.title() + " (" + activity.status() + ")");
            if (isBlocked(status)) {
                blockers.add(point);
                nextActions.add(point(activity, activity.title() + "의 차단 원인을 확인합니다."));
            } else if (isCompleted(status)) {
                completed.add(point);
            } else {
                inProgress.add(point);
                nextActions.add(point(activity, activity.title() + "을(를) 이어서 진행합니다."));
            }
        }

        Instant generatedAt = activities.stream()
                .map(DemoActivity::occurredAt)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        return new WorkSummary(
                "demo-summary-v1",
                request.mode(),
                request.subjectId(),
                request.mode().titleFor(request.subjectName()),
                completed,
                inProgress,
                blockers,
                nextActions,
                generatedAt,
                "fake-v1");
    }

    private static SummaryPoint point(DemoActivity activity, String text) {
        return new SummaryPoint(text, List.of(activity.id()));
    }

    private static boolean isCompleted(String status) {
        return status.contains("done") || status.contains("closed") || status.contains("merged")
                || status.contains("committed")
                || status.contains("resolved") || status.contains("완료") || status.contains("병합");
    }

    private static boolean isBlocked(String status) {
        return status.contains("blocked") || status.contains("blocker") || status.contains("차단")
                || status.contains("막힘");
    }
}
