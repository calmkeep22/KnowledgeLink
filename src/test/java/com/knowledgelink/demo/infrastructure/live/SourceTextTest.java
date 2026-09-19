package com.knowledgelink.demo.infrastructure.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SourceTextTest {

    @Test
    void Jira_마크업에서_코드블록과_스택트레이스와_서식을_걷어낸다() {
        String markup = """
                h2. Summary
                The *RemoteDeleteLag* gauge ({}RemoteDeleteLagBytes{}) stays at {{1}}.
                {code:java}
                long lag = compute();
                {code}
                java.lang.IllegalStateException: boom
                    at org.apache.kafka.Foo.bar(Foo.java:10)
                See [the PR|https://github.com/apache/kafka/pull/1] and [https://example.org/x].
                """;

        assertEquals("Summary The RemoteDeleteLag gauge (RemoteDeleteLagBytes) stays at 1. "
                        + "java.lang.IllegalStateException: boom See the PR and https://example.org/x.",
                SourceText.jira(markup, 800));
    }

    @Test
    void PR_본문은_체크리스트_앞까지만_남기고_주석과_코드펜스를_지운다() {
        String body = """
                <!-- Please describe -->
                ## Summary
                Fix the leak.
                ```java
                close();
                ```
                ### Committer Checklist (excluded from commit message)
                - [ ] Verify design
                """;

        assertEquals("Summary Fix the leak.", SourceText.pullRequest(body, 800));
    }

    @Test
    void 길이를_넘으면_단어_경계에서_자르고_말줄임표를_붙인다() {
        String text = SourceText.truncate("alpha beta gamma delta epsilon", 18);

        assertEquals("alpha beta gamma…", text);
        assertTrue(SourceText.jira(null, 100).isEmpty());
        assertTrue(SourceText.pullRequest("  ", 100).isEmpty());
    }
}
