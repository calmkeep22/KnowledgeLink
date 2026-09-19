package com.knowledgelink.demo.application;

import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import com.knowledgelink.demo.domain.SummaryPoint;
import com.knowledgelink.demo.domain.WorkSummary;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class DemoSummaryService {

    private static final Comparator<DemoActivity> ACTIVITY_ORDER =
            Comparator.comparing(DemoActivity::occurredAt).reversed()
                    .thenComparing(DemoActivity::id);

    private final ActivitySource activitySource;
    private final ActivitySummaryGenerator summaryGenerator;

    public List<DemoActivity> activities() {
        return activitySource.findAll().stream().sorted(ACTIVITY_ORDER).toList();
    }

    public WorkSummary summarizeMember(String memberId) {
        List<DemoActivity> selected = activities().stream()
                .filter(activity -> activity.memberId().equals(memberId))
                .toList();
        if (selected.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return generateAndValidate(new SummaryGenerationRequest(
                SummaryMode.MEMBER, memberId, selected.getFirst().memberName(), selected));
    }

    public WorkSummary summarizeProject(String projectId, SummaryMode mode) {
        if (mode != SummaryMode.PROJECT && mode != SummaryMode.HANDOFF) {
            throw ApiException.invalidField("mode", "PROJECT 또는 HANDOFF만 사용할 수 있습니다.");
        }
        List<DemoActivity> selected = activities().stream()
                .filter(activity -> activity.projectId().equals(projectId))
                .toList();
        if (selected.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return generateAndValidate(new SummaryGenerationRequest(mode, projectId, projectId, selected));
    }

    private WorkSummary generateAndValidate(SummaryGenerationRequest request) {
        WorkSummary summary = summaryGenerator.generate(request);
        if (summary == null) {
            throw new IllegalStateException("요약 생성기가 응답을 반환하지 않았습니다.");
        }

        Set<String> selectedIds = request.activities().stream()
                .map(DemoActivity::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> generatedEvidenceIds = summaryPoints(summary)
                .flatMap(point -> point.evidenceIds().stream())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (!selectedIds.containsAll(generatedEvidenceIds)) {
            throw new IllegalStateException("요약에 선택되지 않은 활동 근거가 포함되어 있습니다.");
        }
        return summary;
    }

    private Stream<SummaryPoint> summaryPoints(WorkSummary summary) {
        return Stream.of(summary.completed(), summary.inProgress(), summary.blockers(), summary.nextActions())
                .flatMap(List::stream);
    }
}
