package com.knowledgelink.demo.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.SummaryGenerationRequest;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import com.knowledgelink.demo.domain.WorkSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeActivitySummaryGeneratorTest {
    private final FakeActivitySummaryGenerator generator = new FakeActivitySummaryGenerator();

    @Test
    void 같은_입력은_근거가_포함된_같은_요약을_만든다() {
        SummaryGenerationRequest request = new SummaryGenerationRequest(
                SummaryMode.MEMBER,
                "member-1",
                "개발자 A",
                List.of(
                        activity("GH-1", "로그인 PR", "MERGED", "2026-09-18T09:00:00Z"),
                        activity("JIRA-2", "권한 오류 조사", "BLOCKED", "2026-09-18T10:00:00Z"),
                        activity("GH-3", "회귀 테스트", "IN_PROGRESS", "2026-09-18T11:00:00Z")));

        WorkSummary first = generator.generate(request);
        WorkSummary second = generator.generate(request);

        assertEquals(first, second);
        assertEquals(Instant.parse("2026-09-18T11:00:00Z"), first.generatedAt());
        assertEquals(List.of("GH-1"), first.completed().getFirst().evidenceIds());
        assertEquals(List.of("JIRA-2"), first.blockers().getFirst().evidenceIds());
        assertEquals(List.of("GH-3"), first.inProgress().getFirst().evidenceIds());
        assertTrue(first.nextActions().stream().allMatch(point -> !point.evidenceIds().isEmpty()));
    }

    @Test
    void 커밋은_완료한_일로_분류한다() {
        SummaryGenerationRequest request = new SummaryGenerationRequest(
                SummaryMode.MEMBER,
                "member-1",
                "개발자 A",
                List.of(activity("GH-1", "결과 카드 추가", "COMMITTED", "2026-09-18T09:00:00Z")));

        WorkSummary summary = generator.generate(request);

        assertEquals(List.of("GH-1"), summary.completed().getFirst().evidenceIds());
        assertTrue(summary.inProgress().isEmpty());
        assertTrue(summary.nextActions().isEmpty());
    }

    private static DemoActivity activity(String id, String title, String status, String occurredAt) {
        return new DemoActivity(
                id,
                "project-1",
                "member-1",
                "개발자 A",
                ActivityKind.GITHUB_PULL_REQUEST,
                title,
                status,
                Instant.parse(occurredAt),
                "https://example.invalid/" + id,
                "가명 데모 데이터");
    }
}
