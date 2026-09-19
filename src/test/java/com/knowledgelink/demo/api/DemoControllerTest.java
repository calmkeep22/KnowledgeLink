package com.knowledgelink.demo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.knowledgelink.demo.application.ActivitySummaryGenerator;
import com.knowledgelink.demo.application.DemoSummaryService;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.SummaryMode;
import com.knowledgelink.demo.domain.WorkSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoControllerTest {

    @Test
    void exposesActivitiesThroughStableWrapper() {
        DemoActivity activity = new DemoActivity(
                "act-1", "project-a", "member-a", "가명 팀원", ActivityKind.JIRA_ISSUE,
                "이슈", "DONE", Instant.parse("2026-09-19T00:00:00Z"),
                "https://example.invalid/jira/TEST-1", "상세");
        ActivitySummaryGenerator unusedGenerator = request -> {
            throw new AssertionError("generator must not be called");
        };
        DemoController controller = new DemoController(new DemoSummaryService(() -> List.of(activity), unusedGenerator));

        ActivityListResponse response = controller.activities();

        assertEquals(List.of(activity), response.activities());
    }

    @Test
    void forwardsProjectSummaryMode() {
        DemoActivity activity = new DemoActivity(
                "act-1", "project-a", "member-a", "가명 팀원", ActivityKind.JIRA_ISSUE,
                "이슈", "DONE", Instant.parse("2026-09-19T00:00:00Z"),
                "https://example.invalid/jira/TEST-1", "상세");
        WorkSummary expected = new WorkSummary(
                "demo-summary-v1", SummaryMode.HANDOFF, "project-a", "project-a",
                List.of(), List.of(), List.of(), List.of(),
                Instant.parse("2026-09-19T00:00:00Z"), "test");
        DemoController controller = new DemoController(new DemoSummaryService(
                () -> List.of(activity), request -> expected));

        assertEquals(expected, controller.projectSummary("project-a", SummaryMode.HANDOFF));
    }
}
