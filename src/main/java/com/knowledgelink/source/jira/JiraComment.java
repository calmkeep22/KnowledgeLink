package com.knowledgelink.source.jira;

import java.time.Instant;
import java.util.Objects;

/** Jira 댓글의 수집 표현. 최근 댓글 20개만 {@link JiraIssue}에 포함한다. */
public record JiraComment(String externalId, String authorAccountId, String body, Instant createdAt,
                          Instant updatedAt) {

    public JiraComment {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("댓글 externalId가 필요합니다.");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
