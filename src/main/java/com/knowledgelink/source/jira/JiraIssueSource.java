package com.knowledgelink.source.jira;

import java.time.Instant;

/** Jira 이슈 페이지를 읽는 outbound port. 구현은 DB transaction 밖에서 호출된다. */
public interface JiraIssueSource {

    JiraPage fetch(JiraSyncTarget target, Instant updatedFrom, String pageToken);
}
