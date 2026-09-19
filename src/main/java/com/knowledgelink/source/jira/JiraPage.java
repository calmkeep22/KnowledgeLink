package com.knowledgelink.source.jira;

import java.util.List;

/** Jira 검색 한 페이지. nextPageToken이 null이면 마지막 페이지다. */
public record JiraPage(List<JiraIssue> issues, String nextPageToken) {

    public JiraPage {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean last() {
        return nextPageToken == null || nextPageToken.isBlank();
    }
}
