package com.knowledgelink.source.jira;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira Cloud REST API v3 adapter. 이슈는 {@code GET /rest/api/3/search/jql}의 nextPageToken으로 넘기고,
 * 검색 결과에 댓글이 다 오지 않은 이슈만 댓글 API로 최근 20개를 다시 읽는다.
 *
 * <p>JQL의 날짜는 분 단위이고 서비스 계정의 시간대로 해석된다. 그래서 계정 시간대를 한 번 읽어 두고,
 * 커서 시각을 그 시간대의 분 단위로 내림한다. 내림으로 넓어진 구간은 upsert가 흡수한다.
 *
 * <p>인증 정보는 호출마다 환경에서 읽고 예외 메시지·로그에 싣지 않는다. redirect는 따라가지 않는다.
 */
public final class JiraCloudIssueSource implements JiraIssueSource {

    static final String SEARCH_PATH = "/rest/api/3/search/jql";
    static final String FIELDS = "summary,description,issuetype,status,resolution,labels,components,"
            + "comment,resolutiondate,updated,project";
    private static final int RECENT_COMMENTS = 20;
    private static final DateTimeFormatter JQL_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Jira는 {@code 2026-09-18T10:15:30.123+0900}처럼 콜론 없는 offset을 쓴다. */
    private static final DateTimeFormatter JIRA_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .appendOffset("+HHMM", "Z")
            .toFormatter();

    private final HttpClient http;
    private final ObjectMapper json;
    private final JiraProperties properties;
    private final Function<String, String> credentials;
    private final Clock clock;
    private final Map<String, ZoneId> accountZones = new ConcurrentHashMap<>();

    /** credentials는 환경 변수 이름을 값으로 바꾼다. 운영에서는 Spring Environment를 넘긴다. */
    public JiraCloudIssueSource(HttpClient http, ObjectMapper json, JiraProperties properties,
                                Function<String, String> credentials, Clock clock) {
        this.http = http;
        this.json = json;
        this.properties = properties;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public JiraPage fetch(JiraSyncTarget target, Instant updatedFrom, String pageToken) {
        String baseUrl = baseUrl(target.baseUrl());
        if (target.projectId() == null || !target.projectId().matches("\\d{1,18}")) {
            // scope external_id는 불변 숫자 project id다. 그 밖의 값은 JQL에 넣지 않는다.
            throw configuration();
        }
        String authorization = authorization(target.credentialRef());
        ZoneId zone = accountZone(baseUrl, target.credentialRef(), authorization);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("jql", "project = " + target.projectId()
                + " AND updated >= \"" + JQL_MINUTE.format(updatedFrom.atZone(zone)) + "\" ORDER BY updated ASC");
        query.put("fields", FIELDS);
        query.put("maxResults", Integer.toString(properties.pageSize()));
        if (pageToken != null) {
            query.put("nextPageToken", pageToken);
        }
        JsonNode page = get(baseUrl, SEARCH_PATH, query, authorization);

        List<JiraIssue> issues = new ArrayList<>();
        for (JsonNode issue : page.path("issues")) {
            issues.add(issue(baseUrl, issue, authorization));
        }
        String next = page.path("isLast").asBoolean(false) ? null : text(page.path("nextPageToken"));
        return new JiraPage(issues, next);
    }

    private JiraIssue issue(String baseUrl, JsonNode issue, String authorization) {
        JsonNode fields = issue.path("fields");
        String id = text(issue.path("id"));
        String key = text(issue.path("key"));
        try {
            return new JiraIssue(id, text(fields.path("project").path("id")), key, text(fields.path("summary")),
                    JiraAdfText.toText(fields.path("description")), text(fields.path("issuetype").path("name")),
                    text(fields.path("status").path("name")), text(fields.path("resolution").path("name")),
                    strings(fields.path("labels"), node -> node), strings(fields.path("components"), node -> node.path("name")),
                    comments(baseUrl, id, fields.path("comment"), authorization),
                    instant(fields.path("resolutiondate")), instant(fields.path("updated")),
                    baseUrl + "/browse/" + key);
        } catch (IllegalArgumentException | NullPointerException | DateTimeException e) {
            // 응답 형식이 계약과 다르다. 공급자 쪽 일시 문제일 수 있어 재시도 한도 안에서 다시 시도한다.
            throw new JiraSourceException(JiraSourceException.Kind.TEMPORARY, null, e);
        }
    }

    private List<JiraComment> comments(String baseUrl, String issueId, JsonNode field, String authorization) {
        JsonNode comments = field.path("comments");
        if (field.path("total").asInt(comments.size()) > comments.size()) {
            if (!issueId.matches("\\d{1,18}")) {
                throw new IllegalArgumentException("Jira issue id 형식이 올바르지 않습니다.");
            }
            comments = get(baseUrl, "/rest/api/3/issue/" + issueId + "/comment",
                    Map.of("orderBy", "-created", "maxResults", Integer.toString(RECENT_COMMENTS)), authorization)
                    .path("comments");
        }
        List<JiraComment> result = new ArrayList<>();
        for (JsonNode comment : comments) {
            result.add(new JiraComment(text(comment.path("id")), text(comment.path("author").path("accountId")),
                    JiraAdfText.toText(comment.path("body")), instant(comment.path("created")),
                    instant(comment.path("updated"))));
        }
        return result;
    }

    private ZoneId accountZone(String baseUrl, String credentialRef, String authorization) {
        String cacheKey = baseUrl + "|" + credentialRef;
        ZoneId cached = accountZones.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String timeZone = text(get(baseUrl, "/rest/api/3/myself", Map.of(), authorization).path("timeZone"));
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (DateTimeException | NullPointerException e) {
            // 시간대를 모르면 JQL 시각을 잘못 해석해 수정분을 놓칠 수 있다. 추측하지 않고 멈춘다.
            throw new JiraSourceException(JiraSourceException.Kind.CONFIGURATION, null, e);
        }
        accountZones.putIfAbsent(cacheKey, zone);
        return zone;
    }

    private JsonNode get(String baseUrl, String path, Map<String, String> query, String authorization) {
        String queryString = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path + (queryString.isEmpty() ? "" : "?" + queryString)))
                .timeout(properties.timeout())
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraSourceException(JiraSourceException.Kind.TEMPORARY, null, e);
        } catch (IOException e) {
            throw new JiraSourceException(JiraSourceException.Kind.TEMPORARY, null, e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return json.readTree(response.body());
            } catch (JacksonException e) {
                throw new JiraSourceException(JiraSourceException.Kind.TEMPORARY, null, e);
            }
        }
        throw error(response);
    }

    private JiraSourceException error(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status == 429) {
            return new JiraSourceException(JiraSourceException.Kind.RATE_LIMIT, retryAfter(response));
        }
        if (status == 401 || status == 403) {
            return new JiraSourceException(JiraSourceException.Kind.AUTHENTICATION, null);
        }
        if (status == 408 || status >= 500) {
            return new JiraSourceException(JiraSourceException.Kind.TEMPORARY, retryAfter(response));
        }
        // 400(없는 프로젝트·잘못된 JQL), 404 등은 다시 불러도 같다.
        return configuration();
    }

    /** Retry-After(초)를 우선하고, 없으면 X-RateLimit-Reset(ISO 시각)까지 기다린다. 둘 다 없으면 기본 backoff. */
    Duration retryAfter(HttpResponse<?> response) {
        String seconds = response.headers().firstValue("Retry-After").orElse(null);
        if (seconds != null) {
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(seconds.strip())));
            } catch (NumberFormatException ignored) {
                // HTTP-date 형식은 Jira가 쓰지 않는다. reset 헤더로 넘어간다.
            }
        }
        String reset = response.headers().firstValue("X-RateLimit-Reset").orElse(null);
        if (reset != null) {
            try {
                Duration untilReset = Duration.between(clock.instant(), OffsetDateTime.parse(reset.strip()).toInstant());
                return untilReset.isNegative() ? Duration.ZERO : untilReset;
            } catch (DateTimeException ignored) {
                // 알 수 없는 형식이면 기본 backoff를 쓴다.
            }
        }
        return Duration.ZERO;
    }

    private String authorization(String credentialRef) {
        String raw = credentialRef == null ? null : credentials.apply(credentialRef);
        String value = raw == null ? "" : raw.strip();
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            // 값이 없거나 "이메일:토큰" 형식이 아니다. 값은 메시지에 싣지 않는다.
            throw configuration();
        }
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 연결 생성 시 검증하지만 인증 헤더를 보내기 전에 한 번 더 막는다. */
    private static String baseUrl(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw configuration();
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (host == null || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null) {
            throw configuration();
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static Instant instant(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, JIRA_TIME).toInstant();
        } catch (DateTimeException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    private static List<String> strings(JsonNode array, Function<JsonNode, JsonNode> value) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            String text = text(value.apply(item));
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value == null || value.isBlank() ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JiraSourceException configuration() {
        return new JiraSourceException(JiraSourceException.Kind.CONFIGURATION, null);
    }
}
