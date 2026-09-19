package com.knowledgelink.demo.infrastructure.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.knowledgelink.demo.application.SimilarWorkService;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import com.knowledgelink.demo.domain.PastWorkSourceInfo.ExampleQuery;
import com.knowledgelink.demo.infrastructure.MockPastWorkSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LivePastWorkSourceTest {

    private static final String PULLS_PAGE_1 = """
            [
              {"number": 101, "title": "KAFKA-1: Fix consumer memory leak on close", "body": "Close the fetch buffers.\\n\\n### Committer Checklist\\n- [ ] tests",
               "user": {"login": "alice-gh"}, "merged_at": "2026-09-10T01:00:00Z", "html_url": "https://github.com/apache/kafka/pull/101"},
              {"number": 102, "title": "MINOR: tidy docs", "body": null,
               "user": {"login": "bob"}, "merged_at": "2026-09-11T01:00:00Z", "html_url": "https://github.com/apache/kafka/pull/102"},
              {"number": 103, "title": "KAFKA-2: Handle DNS failure", "body": "<!-- template -->Retry bootstrap resolution.",
               "user": {"login": "carol"}, "merged_at": null, "html_url": "https://github.com/apache/kafka/pull/103"},
              {"number": 104, "title": "KAFKA-3 KAFKA-1: follow-up for leak", "body": "Also release metrics.",
               "user": {"login": "dave"}, "merged_at": "2026-09-12T01:00:00Z", "html_url": "https://github.com/apache/kafka/pull/104"}
            ]
            """;

    private static final String JIRA_SEARCH = """
            {"issues": [
              {"key": "KAFKA-1", "fields": {
                "summary": "Consumer leaks memory after close", "description": "h2. Problem\\n*Heap* grows. {code}stack{code} See [PR|https://x]",
                "issuetype": {"name": "Bug"}, "status": {"name": "Resolved"}, "resolution": {"name": "Fixed"},
                "resolutiondate": "2026-09-12T02:00:00.000+0000", "assignee": {"name": "alice", "displayName": "Alice Kim"}}},
              {"key": "KAFKA-3", "fields": {
                "summary": "Metrics not released", "description": null,
                "issuetype": {"name": "Bug"}, "status": {"name": "Closed"}, "resolution": {"name": "Fixed"},
                "resolutiondate": "2026-09-13T02:00:00.000+0000", "assignee": null}}
            ]}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void 병합_PR의_Jira_키로_해결된_이슈를_찾아_이슈와_PR을_서로_연결한다() {
        List<URI> requested = new ArrayList<>();
        LivePastWorkSource source = source(fakeHttp(requested, new AtomicBoolean(false)), "");

        List<DemoActivity> items = source.findAll();
        Map<String, List<String>> links = source.links();

        assertEquals(List.of("KAFKA-3", "PR-104", "KAFKA-1", "PR-101"), items.stream().map(DemoActivity::id).toList());
        assertEquals(List.of("PR-101", "PR-104"), links.get("KAFKA-1"));
        assertEquals(List.of("KAFKA-3", "KAFKA-1"), links.get("PR-104"));

        DemoActivity issue = items.stream().filter(item -> item.id().equals("KAFKA-1")).findFirst().orElseThrow();
        assertEquals(ActivityKind.JIRA_ISSUE, issue.kind());
        assertEquals("RESOLVED", issue.status());
        assertEquals("jira:alice", issue.memberId());
        assertEquals("https://issues.apache.org/jira/browse/KAFKA-1", issue.sourceUrl());
        assertEquals("Problem Heap grows. See PR", issue.details());

        DemoActivity unassigned = items.stream().filter(item -> item.id().equals("KAFKA-3")).findFirst().orElseThrow();
        assertEquals(SimilarWorkService.UNASSIGNED_MEMBER_ID, unassigned.memberId());

        DemoActivity pullRequest = items.stream().filter(item -> item.id().equals("PR-101")).findFirst().orElseThrow();
        assertEquals("MERGED", pullRequest.status());
        assertEquals("Close the fetch buffers.", pullRequest.details());

        // 병합되지 않은 PR(103)과 키가 없는 PR(102)은 이슈 조회 대상이 아니다.
        String jql = requested.stream().filter(uri -> uri.getHost().equals("issues.apache.org"))
                .map(uri -> URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8)).findFirst().orElseThrow();
        assertTrue(jql.contains("key in (KAFKA-1,KAFKA-3)"), jql);
        assertTrue(jql.contains("issuetype in (\"Bug\")"), jql);
    }

    @Test
    void 출처_정보에는_실제_수집_여부와_연결_수와_예시_질문이_담긴다() {
        LivePastWorkSource source = source(fakeHttp(new ArrayList<>(), new AtomicBoolean(false)), "");

        PastWorkSourceInfo info = source.info();

        assertEquals("live", info.sourceType());
        assertEquals("Apache Kafka 공개 Jira·GitHub", info.label());
        assertEquals(2, info.issueCount());
        assertEquals(2, info.pullRequestCount());
        assertEquals(2, info.linkedIssueCount());
        assertEquals(clock.instant(), info.fetchedAt());
        assertEquals("메모리 누수", info.examples().getFirst().label());
    }

    @Test
    void 수집에_성공하면_저장본을_남기고_다음_수집이_실패하면_저장본을_쓴다(@TempDir Path directory) {
        Path snapshot = directory.resolve("nested/past-work.json");
        source(fakeHttp(new ArrayList<>(), new AtomicBoolean(false)), snapshot.toString()).findAll();
        assertTrue(Files.isRegularFile(snapshot));

        LivePastWorkSource offline = source(fakeHttp(new ArrayList<>(), new AtomicBoolean(true)), snapshot.toString());

        assertEquals(4, offline.findAll().size());
        assertEquals(List.of("PR-101", "PR-104"), offline.links().get("KAFKA-1"));
        assertEquals("snapshot", offline.info().sourceType());
    }

    @Test
    void 저장본이_기준보다_새로우면_수집하지_않고_오래됐으면_다시_수집한다(@TempDir Path directory) {
        Path snapshot = directory.resolve("past-work.json");
        source(fakeHttp(new ArrayList<>(), new AtomicBoolean(false)), snapshot.toString()).findAll();

        List<URI> requested = new ArrayList<>();
        Clock twoHoursLater = Clock.offset(clock, Duration.ofHours(2));
        LivePastWorkSource fresh = source(fakeHttp(requested, new AtomicBoolean(false)), snapshot.toString(),
                Duration.ofHours(12), twoHoursLater);
        assertEquals(4, fresh.findAll().size());
        assertEquals("snapshot", fresh.info().sourceType());
        assertTrue(requested.isEmpty());

        Clock dayLater = Clock.offset(clock, Duration.ofHours(24));
        LivePastWorkSource stale = source(fakeHttp(requested, new AtomicBoolean(false)), snapshot.toString(),
                Duration.ofHours(12), dayLater);
        assertEquals("live", stale.info().sourceType());
        assertFalse(requested.isEmpty());
    }

    @Test
    void 수집도_저장본도_없으면_가명_예시_데이터를_쓴다(@TempDir Path directory) {
        LivePastWorkSource offline = source(fakeHttp(new ArrayList<>(), new AtomicBoolean(true)),
                directory.resolve("missing.json").toString());

        assertFalse(offline.findAll().isEmpty());
        assertTrue(offline.findAll().stream().allMatch(item -> item.id().startsWith("past-")));
        assertEquals("mock", offline.info().sourceType());
    }

    private LivePastWorkSource source(HttpGetter http, String snapshotPath) {
        return source(http, snapshotPath, Duration.ZERO, clock);
    }

    private LivePastWorkSource source(HttpGetter http, String snapshotPath, Duration maxAge, Clock clock) {
        LivePastWorkProperties.Apache properties = new LivePastWorkProperties.Apache(
                URI.create("https://issues.apache.org/jira/"), "KAFKA", "Apache Kafka", "apache/kafka", "",
                1, 200, List.of("Bug"), snapshotPath, maxAge, Duration.ofSeconds(5), 800,
                List.of(new ExampleQuery("메모리 누수", "닫은 뒤에도 메모리가 늘어요")));
        return new LivePastWorkSource(
                new GitHubPullRequestClient(http, objectMapper, properties.githubRepository(), ""),
                new ApacheJiraClient(http, objectMapper, properties.jiraBase()),
                properties,
                objectMapper,
                new MockPastWorkSource(objectMapper),
                clock);
    }

    private static HttpGetter fakeHttp(List<URI> requested, AtomicBoolean offline) {
        return (uri, headers) -> {
            requested.add(uri);
            if (offline.get()) {
                throw new LiveSourceException("offline");
            }
            if (uri.getHost().equals("api.github.com")) {
                assertEquals("KnowledgeLink-hackathon-demo", headers.get("User-Agent"));
                assertFalse(headers.containsKey("Authorization"));
                return PULLS_PAGE_1;
            }
            if (uri.getHost().equals("issues.apache.org")) {
                assertTrue(uri.getPath().endsWith("/rest/api/2/search"));
                return JIRA_SEARCH;
            }
            throw new AssertionError("unexpected host " + uri);
        };
    }
}
