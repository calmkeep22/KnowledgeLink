package com.knowledgelink.demo.infrastructure.live;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 공개 Jira(Server/Data Center) REST API v2로 이슈를 익명 조회한다. Apache Jira처럼 로그인 없이 읽을 수 있는 곳만 대상이다.
 * JQL에는 형식을 검증한 이슈 키와 이슈 유형 이름만 넣는다.
 */
final class ApacheJiraClient {
    private static final int BATCH = 50;
    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9]{1,19}-\\d{1,7}");
    private static final Pattern ISSUE_TYPE = Pattern.compile("[A-Za-z -]{1,40}");
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final String FIELDS = "summary,description,issuetype,status,resolution,resolutiondate,assignee";

    record JiraIssue(String key, String summary, String description, String type, String status,
                     String resolution, Instant resolvedAt, String assigneeId, String assigneeName) {
    }

    private final HttpGetter http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    ApacheJiraClient(HttpGetter http, ObjectMapper objectMapper, String baseUrl) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /** 이슈 키 목록 중 지정한 유형이고 해결된 이슈만 읽는다. */
    List<JiraIssue> fetchResolved(Collection<String> keys, List<String> issueTypes) {
        List<String> validKeys = keys.stream().filter(key -> ISSUE_KEY.matcher(key).matches()).distinct().toList();
        String typeClause = issueTypes.stream()
                .filter(type -> ISSUE_TYPE.matcher(type).matches())
                .map(type -> "\"" + type + "\"")
                .collect(Collectors.joining(","));
        List<JiraIssue> result = new ArrayList<>();
        for (int start = 0; start < validKeys.size(); start += BATCH) {
            List<String> batch = validKeys.subList(start, Math.min(start + BATCH, validKeys.size()));
            String jql = "key in (" + String.join(",", batch) + ") AND resolution is not EMPTY"
                    + (typeClause.isEmpty() ? "" : " AND issuetype in (" + typeClause + ")");
            URI uri = URI.create(baseUrl + "/rest/api/2/search?jql=" + encode(jql)
                    + "&maxResults=" + BATCH + "&fields=" + encode(FIELDS));
            result.addAll(parse(http.get(uri, Map.of("Accept", "application/json"))));
        }
        return result;
    }

    List<JiraIssue> parse(String json) {
        JsonNode issues = objectMapper.readTree(json).path("issues");
        if (!issues.isArray()) {
            throw new LiveSourceException("Jira 검색 응답 형식이 올바르지 않습니다.");
        }
        List<JiraIssue> result = new ArrayList<>();
        for (JsonNode issue : issues) {
            JsonNode fields = issue.path("fields");
            String resolvedAt = text(fields.path("resolutiondate"));
            if (resolvedAt.isEmpty()) {
                continue;
            }
            JsonNode assignee = fields.path("assignee");
            result.add(new JiraIssue(
                    issue.path("key").asText(),
                    text(fields.path("summary")),
                    text(fields.path("description")),
                    text(fields.path("issuetype").path("name")),
                    text(fields.path("status").path("name")),
                    text(fields.path("resolution").path("name")),
                    OffsetDateTime.parse(resolvedAt, JIRA_TIME).toInstant(),
                    text(assignee.path("name")),
                    text(assignee.path("displayName"))));
        }
        return result;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.asText("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
