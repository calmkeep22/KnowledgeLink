package com.knowledgelink.demo.application;

import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import com.knowledgelink.demo.domain.SummaryPoint;
import com.knowledgelink.demo.domain.WorkSummary;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class DemoSummaryService {

    private static final Comparator<DemoActivity> ACTIVITY_ORDER =
            Comparator.comparing(DemoActivity::occurredAt).reversed()
                    .thenComparing(DemoActivity::id);

    private final ActivitySource activitySource;
    private final ActivitySummaryGenerator summaryGenerator;

    // 요청 record는 선택된 활동 목록까지 값으로 비교하므로 입력 활동이 바뀌면 자연히 새 키가 된다.
    // 같은 화면을 반복해서 눌러도 유료 AI를 다시 호출하지 않는다. 실패는 캐시하지 않는다.
    private final Map<SummaryGenerationRequest, WorkSummary> summaryCache = new ConcurrentHashMap<>();

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
        return summarize(new SummaryGenerationRequest(
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
        return summarize(new SummaryGenerationRequest(mode, projectId, projectId, selected));
    }

    private WorkSummary summarize(SummaryGenerationRequest request) {
        WorkSummary cached = summaryCache.get(request);
        if (cached != null) {
            return cached;
        }
        try {
            WorkSummary summary = generateAndValidate(request);
            summaryCache.putIfAbsent(request, summary);
            return summary;
        } catch (ActivitySummaryGenerationException exception) {
            log.warn("Demo summary generation failed: mode={}, subjectId={}",
                    request.mode(), request.subjectId(), exception);
            throw new ApiException(ErrorCode.TEMPORARY_UNAVAILABLE);
        }
    }

    private WorkSummary generateAndValidate(SummaryGenerationRequest request) {
        WorkSummary summary = summaryGenerator.generate(request);
        if (summary == null) {
            throw new ActivitySummaryGenerationException("요약 생성기가 응답을 반환하지 않았습니다.");
        }

        Set<String> selectedIds = request.activities().stream()
                .map(DemoActivity::id)
                .collect(Collectors.toUnmodifiableSet());
        boolean allEvidenceSelected = summaryPoints(summary)
                .flatMap(point -> point.evidenceIds().stream())
                .allMatch(selectedIds::contains);
        if (!allEvidenceSelected) {
            throw new ActivitySummaryGenerationException("요약에 선택되지 않은 활동 근거가 포함되어 있습니다.");
        }
        return summary;
    }

    private Stream<SummaryPoint> summaryPoints(WorkSummary summary) {
        return Stream.of(summary.completed(), summary.inProgress(), summary.blockers(), summary.nextActions())
                .flatMap(List::stream);
    }
}
