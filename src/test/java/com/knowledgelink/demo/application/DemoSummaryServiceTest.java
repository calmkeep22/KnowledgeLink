package com.knowledgelink.demo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import com.knowledgelink.demo.domain.SummaryPoint;
import com.knowledgelink.demo.domain.WorkSummary;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DemoSummaryServiceTest {

    @Test
    void activitiesAreSortedNewestFirstAndUseIdAsTieBreaker() {
        DemoActivity older = activity("act-c", "project-a", "member-a", "2026-09-16T00:00:00Z");
        DemoActivity sameTimeLaterId = activity("act-b", "project-a", "member-a", "2026-09-18T00:00:00Z");
        DemoActivity sameTimeEarlierId = activity("act-a", "project-a", "member-a", "2026-09-18T00:00:00Z");
        DemoSummaryService service = service(List.of(older, sameTimeLaterId, sameTimeEarlierId), request -> summary(request, "act-a"));

        assertEquals(List.of("act-a", "act-b", "act-c"),
                service.activities().stream().map(DemoActivity::id).toList());
    }

    @Test
    void memberSummarySendsOnlySelectedMemberActivitiesInDeterministicOrder() {
        DemoActivity selectedOld = activity("act-1", "project-a", "member-a", "2026-09-16T00:00:00Z");
        DemoActivity other = activity("act-2", "project-a", "member-b", "2026-09-19T00:00:00Z");
        DemoActivity selectedNew = activity("act-3", "project-a", "member-a", "2026-09-18T00:00:00Z");
        AtomicReference<SummaryGenerationRequest> captured = new AtomicReference<>();
        DemoSummaryService service = service(List.of(selectedOld, other, selectedNew), request -> {
            captured.set(request);
            return summary(request, "act-3");
        });

        WorkSummary result = service.summarizeMember("member-a");

        assertEquals(SummaryMode.MEMBER, result.mode());
        assertEquals(List.of("act-3", "act-1"),
                captured.get().activities().stream().map(DemoActivity::id).toList());
    }

    @Test
    void projectSummaryRejectsMemberModeBeforeCallingGenerator() {
        DemoSummaryService service = service(
                List.of(activity("act-1", "project-a", "member-a", "2026-09-18T00:00:00Z")),
                request -> { throw new AssertionError("generator must not be called"); });

        ApiException exception = assertThrows(ApiException.class,
                () -> service.summarizeProject("project-a", SummaryMode.MEMBER));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    @Test
    void missingSubjectReturnsNotFound() {
        DemoSummaryService service = service(
                List.of(activity("act-1", "project-a", "member-a", "2026-09-18T00:00:00Z")),
                request -> { throw new AssertionError("generator must not be called"); });

        ApiException exception = assertThrows(ApiException.class,
                () -> service.summarizeMember("missing"));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void generatedEvidenceMustBelongToSelectedActivities() {
        DemoActivity selected = activity("act-1", "project-a", "member-a", "2026-09-18T00:00:00Z");
        DemoSummaryService service = service(List.of(selected), request -> summary(request, "act-from-other-scope"));

        ApiException exception = assertThrows(ApiException.class, () -> service.summarizeMember("member-a"));

        assertEquals(ErrorCode.TEMPORARY_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void providerFailureBecomesRetryableUnavailable() {
        DemoSummaryService service = service(
                List.of(activity("act-1", "project-a", "member-a", "2026-09-18T00:00:00Z")),
                request -> { throw new ActivitySummaryGenerationException("공급자 실패"); });

        ApiException exception = assertThrows(ApiException.class, () -> service.summarizeMember("member-a"));

        assertEquals(ErrorCode.TEMPORARY_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void sameSummaryRequestCallsGeneratorOnce() {
        AtomicInteger calls = new AtomicInteger();
        DemoSummaryService service = service(
                List.of(activity("act-1", "project-a", "member-a", "2026-09-18T00:00:00Z")),
                request -> {
                    calls.incrementAndGet();
                    return summary(request, "act-1");
                });

        WorkSummary first = service.summarizeMember("member-a");
        WorkSummary second = service.summarizeMember("member-a");
        service.summarizeProject("project-a", SummaryMode.PROJECT);
        service.summarizeProject("project-a", SummaryMode.HANDOFF);

        assertSame(first, second);
        assertEquals(3, calls.get());
    }

    @Test
    void failedSummaryIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        DemoSummaryService service = service(
                List.of(activity("act-1", "project-a", "member-a", "2026-09-18T00:00:00Z")),
                request -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new ActivitySummaryGenerationException("일시 실패");
                    }
                    return summary(request, "act-1");
                });

        assertThrows(ApiException.class, () -> service.summarizeMember("member-a"));
        WorkSummary retried = service.summarizeMember("member-a");

        assertEquals(SummaryMode.MEMBER, retried.mode());
        assertEquals(2, calls.get());
    }

    private DemoSummaryService service(List<DemoActivity> activities, ActivitySummaryGenerator generator) {
        return new DemoSummaryService(() -> activities, generator);
    }

    private DemoActivity activity(String id, String projectId, String memberId, String occurredAt) {
        return new DemoActivity(
                id,
                projectId,
                memberId,
                "가명 팀원",
                ActivityKind.GITHUB_COMMIT,
                "테스트 활동",
                "DONE",
                Instant.parse(occurredAt),
                "https://example.invalid/activities/" + id,
                "테스트 상세");
    }

    private WorkSummary summary(SummaryGenerationRequest request, String evidenceId) {
        return new WorkSummary(
                "demo-summary-v1",
                request.mode(),
                request.subjectId(),
                request.subjectName(),
                List.of(new SummaryPoint("완료한 일", List.of(evidenceId))),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-09-19T00:00:00Z"),
                "test");
    }
}
