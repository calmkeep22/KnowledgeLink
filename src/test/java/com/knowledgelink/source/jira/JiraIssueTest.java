package com.knowledgelink.source.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JiraIssueTest {

    @Test
    void 댓글은_최신순_20개만_보관한다() {
        List<JiraComment> comments = IntStream.range(0, 25)
                .mapToObj(index -> new JiraComment("c" + index, "a", "body", Instant.EPOCH.plusSeconds(index),
                        Instant.EPOCH.plusSeconds(index)))
                .toList();

        JiraIssue issue = new JiraIssue("1", "10000", "PAY-1", "title", "body", "Task", "Open", null,
                List.of(), List.of(), comments, null, Instant.EPOCH, "https://example.atlassian.net/browse/PAY-1");

        assertThat(issue.comments()).hasSize(20);
        assertThat(issue.comments().getFirst().externalId()).isEqualTo("c24");
        assertThat(issue.comments().getLast().externalId()).isEqualTo("c5");
    }
}
