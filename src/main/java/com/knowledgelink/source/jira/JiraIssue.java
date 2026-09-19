package com.knowledgelink.source.jira;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Jira API 응답을 공급자 중립 저장 형식으로 옮긴 값. externalId와 projectId는 Jira의 불변 ID다. */
public record JiraIssue(String externalId, String projectId, String issueKey, String title, String body,
                        String type, String status, String resolution, List<String> labels,
                        List<String> components, List<JiraComment> comments, Instant resolvedAt,
                        Instant updatedAt, String url) {

    public JiraIssue {
        require(externalId, "externalId");
        require(projectId, "projectId");
        require(issueKey, "issueKey");
        require(title, "title");
        require(type, "type");
        require(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        require(url, "url");
        labels = labels == null ? List.of() : labels.stream().sorted().distinct().toList();
        components = components == null ? List.of() : components.stream().sorted().distinct().toList();
        comments = comments == null ? List.of() : comments.stream()
                .sorted(java.util.Comparator.comparing(JiraComment::createdAt).reversed())
                .limit(20)
                .toList();
    }

    public JiraCursor cursor() {
        return new JiraCursor(updatedAt, externalId);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 필요합니다.");
        }
    }
}
