package com.knowledgelink.demo.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 화면에 표시할 과거 업무 출처. 실제 수집 자료인지, 언제 가져왔는지, 이슈와 PR이 몇 쌍 연결됐는지,
 * 이 자료에 맞는 예시 질문이 무엇인지 알려 준다.
 */
public record PastWorkSourceInfo(
        String label,
        String sourceType,
        Instant fetchedAt,
        int issueCount,
        int pullRequestCount,
        int linkedIssueCount,
        List<ExampleQuery> examples
) {
    public record ExampleQuery(String label, String query) {
    }

    public PastWorkSourceInfo {
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    public static PastWorkSourceInfo of(String label, String sourceType, Instant fetchedAt,
                                        List<DemoActivity> items, Map<String, List<String>> links,
                                        List<ExampleQuery> examples) {
        int issues = (int) items.stream().filter(item -> item.kind() == ActivityKind.JIRA_ISSUE).count();
        int pullRequests = (int) items.stream().filter(item -> item.kind() == ActivityKind.GITHUB_PULL_REQUEST).count();
        int linkedIssues = (int) items.stream()
                .filter(item -> item.kind() == ActivityKind.JIRA_ISSUE)
                .filter(item -> !links.getOrDefault(item.id(), List.of()).isEmpty())
                .count();
        return new PastWorkSourceInfo(label, sourceType, fetchedAt, issues, pullRequests, linkedIssues, examples);
    }
}
