package com.knowledgelink.demo.api;

import com.knowledgelink.demo.application.DemoSummaryService;
import com.knowledgelink.demo.domain.SummaryMode;
import com.knowledgelink.demo.domain.WorkSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kl.demo.enabled", havingValue = "true")
public class DemoController {

    private final DemoSummaryService summaryService;

    @GetMapping("/activities")
    public ActivityListResponse activities() {
        return new ActivityListResponse(summaryService.activities());
    }

    @GetMapping("/members/{memberId}/summary")
    public WorkSummary memberSummary(@PathVariable String memberId) {
        return summaryService.summarizeMember(memberId);
    }

    @GetMapping("/projects/{projectId}/summary")
    public WorkSummary projectSummary(@PathVariable String projectId,
                                      @RequestParam(defaultValue = "PROJECT") SummaryMode mode) {
        return summaryService.summarizeProject(projectId, mode);
    }
}
