package com.knowledgelink.demo.infrastructure.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApacheJiraClientTest {

    @Test
    void 형식이_맞는_키와_유형만_JQL에_넣고_50개씩_나눠_조회한다() {
        List<String> jqls = new ArrayList<>();
        ApacheJiraClient client = new ApacheJiraClient((uri, headers) -> {
            jqls.add(URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
            return "{\"issues\": []}";
        }, new ObjectMapper(), "https://issues.apache.org/jira");
        List<String> keys = new ArrayList<>(IntStream.rangeClosed(1, 60).mapToObj(i -> "KAFKA-" + i).toList());
        keys.add("KAFKA-1) OR project = SECRET AND (key = X-1");
        keys.add("kafka-99");

        client.fetchResolved(keys, List.of("Bug", "Bug\") OR (1=1"));

        assertEquals(2, jqls.size());
        assertTrue(jqls.getFirst().contains("KAFKA-50)"), jqls.getFirst());
        assertTrue(jqls.get(1).startsWith("jql=key in (KAFKA-51,"), jqls.get(1));
        assertTrue(jqls.stream().allMatch(jql -> jql.contains("issuetype in (\"Bug\")")));
        assertTrue(jqls.stream().allMatch(jql -> jql.contains("resolution is not EMPTY")));
        assertFalse(jqls.stream().anyMatch(jql -> jql.contains("SECRET") || jql.contains("kafka-99") || jql.contains("1=1")));
    }

    @Test
    void 해결일이_없는_이슈는_건너뛰고_형식이_틀린_응답은_거절한다() {
        ApacheJiraClient client = new ApacheJiraClient((uri, headers) -> "", new ObjectMapper(), "https://x");
        String json = """
                {"issues": [
                  {"key": "KAFKA-1", "fields": {"summary": "a", "resolutiondate": null, "assignee": null}},
                  {"key": "KAFKA-2", "fields": {"summary": "b", "resolutiondate": "2026-01-02T03:04:05.000+0900",
                    "status": {"name": "Resolved"}, "assignee": {"name": "u", "displayName": "User"}}}
                ]}
                """;

        List<ApacheJiraClient.JiraIssue> issues = client.parse(json);

        assertEquals(1, issues.size());
        assertEquals("KAFKA-2", issues.getFirst().key());
        assertEquals("2026-01-01T18:04:05Z", issues.getFirst().resolvedAt().toString());
        assertThrows(LiveSourceException.class, () -> client.parse("{\"errorMessages\": [\"x\"]}"));
    }

    @Test
    void 요청은_REST_v2_검색_주소로_보낸다() {
        List<URI> uris = new ArrayList<>();
        new ApacheJiraClient((uri, headers) -> {
            uris.add(uri);
            return "{\"issues\": []}";
        }, new ObjectMapper(), "https://issues.apache.org/jira").fetchResolved(List.of("KAFKA-1"), List.of());

        assertEquals("/jira/rest/api/2/search", uris.getFirst().getPath());
        assertFalse(URLDecoder.decode(uris.getFirst().getRawQuery(), StandardCharsets.UTF_8).contains("issuetype in"));
    }
}
