package com.knowledgelink.source.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Jira Cloud 응답 형식(src/test/resources/jira)을 로컬 HTTP 서버로 돌려주는 계약 테스트. */
class JiraCloudIssueSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T02:00:00Z");
    private static final String CREDENTIAL = "svc@example.com:api-token-123";

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private Function<Request, Response> responder;
    private HttpServer server;
    private String baseUrl;
    private Map<String, String> environment;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        environment = Map.of("JIRA_DEMO_CREDENTIAL", CREDENTIAL);
        responder = request -> switch (request.path()) {
            case "/rest/api/3/myself" -> Response.ok("myself.json");
            case JiraCloudIssueSource.SEARCH_PATH -> Response.ok(request.query().containsKey("nextPageToken")
                    ? "search-page-2.json" : "search-page-1.json");
            case "/rest/api/3/issue/10002/comment" -> Response.ok("comments-recent.json");
            default -> new Response(404, "{}", Map.of());
        };
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 첫_페이지는_계정_시간대의_분_단위_JQL로_검색하고_인증_헤더를_보낸다() {
        source().fetch(target("10000"), Instant.parse("2026-09-18T01:00:30Z"), null);

        Request search = only(JiraCloudIssueSource.SEARCH_PATH);
        // UTC 01:00:30 → 계정 시간대(Asia/Seoul) 10:00, 분 단위 내림.
        assertThat(search.query()).containsEntry("jql",
                "project = 10000 AND updated >= \"2026-09-18 10:00\" ORDER BY updated ASC");
        assertThat(search.query()).containsEntry("fields", JiraCloudIssueSource.FIELDS);
        assertThat(search.query()).containsEntry("maxResults", "100");
        assertThat(search.query()).doesNotContainKey("nextPageToken");
        assertThat(search.authorization()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString(CREDENTIAL.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 응답을_공급자_중립_형식으로_옮기고_ADF를_평문으로_바꾼다() {
        JiraPage page = source().fetch(target("10000"), Instant.EPOCH, null);

        assertThat(page.nextPageToken()).isEqualTo("page-token-2");
        assertThat(page.last()).isFalse();
        JiraIssue first = page.issues().getFirst();
        assertThat(first.externalId()).isEqualTo("10001");
        assertThat(first.projectId()).isEqualTo("10000");
        assertThat(first.issueKey()).isEqualTo("PAY-1");
        assertThat(first.title()).isEqualTo("결제 승인 요청 timeout");
        assertThat(first.body()).isEqualTo("""
                결제 승인 요청이 `timeout` 난다. 로그: 대시보드 (https://grafana.example.com/d/pay)

                ```java
                client.approve(order);
                ```

                - 재시도 추가""");
        assertThat(first.type()).isEqualTo("Bug");
        assertThat(first.status()).isEqualTo("Done");
        assertThat(first.resolution()).isEqualTo("Fixed");
        assertThat(first.labels()).containsExactly("backend", "payment");
        assertThat(first.components()).containsExactly("api", "billing");
        assertThat(first.resolvedAt()).isEqualTo(Instant.parse("2026-09-18T01:05:00Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-09-18T01:06:00.123Z"));
        assertThat(first.url()).isEqualTo(baseUrl + "/browse/PAY-1");
        assertThat(first.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.externalId()).isEqualTo("20001");
            assertThat(comment.authorAccountId()).isEqualTo("account-a");
            assertThat(comment.body()).isEqualTo("재현했습니다.");
            assertThat(comment.createdAt()).isEqualTo(Instant.parse("2026-09-17T00:00:00Z"));
        });

        JiraIssue second = page.issues().get(1);
        assertThat(second.body()).isNull();
        assertThat(second.resolution()).isNull();
        assertThat(second.resolvedAt()).isNull();
        assertThat(second.labels()).isEmpty();
    }

    @Test
    void 검색_결과에_댓글이_다_오지_않으면_댓글_API로_최근_20개를_다시_읽는다() {
        JiraPage page = source().fetch(target("10000"), Instant.EPOCH, null);

        Request comments = only("/rest/api/3/issue/10002/comment");
        assertThat(comments.query()).containsEntry("orderBy", "-created").containsEntry("maxResults", "20");
        assertThat(requests).noneMatch(request -> request.path().equals("/rest/api/3/issue/10001/comment"));
        assertThat(page.issues().get(1).comments()).extracting(JiraComment::externalId)
                .containsExactly("30025", "30024");
        assertThat(page.issues().get(1).comments().getFirst().body()).isEqualTo("@개발자 A 배치 주기를 줄였습니다.");
    }

    @Test
    void 다음_페이지는_토큰을_넘기고_isLast면_끝이며_계정_시간대는_한_번만_읽는다() {
        JiraCloudIssueSource source = source();
        source.fetch(target("10000"), Instant.EPOCH, null);

        JiraPage page = source.fetch(target("10000"), Instant.EPOCH, "page-token-2");

        assertThat(requests.stream().filter(request -> request.path().equals(JiraCloudIssueSource.SEARCH_PATH))
                .toList().getLast().query()).containsEntry("nextPageToken", "page-token-2");
        assertThat(page.last()).isTrue();
        assertThat(page.issues()).extracting(JiraIssue::issueKey).containsExactly("PAY-3");
        assertThat(requests).filteredOn(request -> request.path().equals("/rest/api/3/myself")).hasSize(1);
    }

    @Test
    void 요청_제한은_Retry_After를_따른다() {
        failSearchWith(new Response(429, "{}", Map.of("Retry-After", "42")));

        assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                .isInstanceOfSatisfying(JiraSourceException.class, error -> {
                    assertThat(error.kind()).isEqualTo(JiraSourceException.Kind.RATE_LIMIT);
                    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(42));
                });
    }

    @Test
    void Retry_After가_없으면_재설정_시각까지_기다린다() {
        failSearchWith(new Response(429, "{}", Map.of("X-RateLimit-Reset", "2026-09-18T02:01:30Z")));

        assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                .isInstanceOfSatisfying(JiraSourceException.class,
                        error -> assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(90)));
    }

    @Test
    void 상태_코드를_재시도_정책으로_분류한다() {
        assertKind(401, JiraSourceException.Kind.AUTHENTICATION);
        assertKind(403, JiraSourceException.Kind.AUTHENTICATION);
        assertKind(400, JiraSourceException.Kind.CONFIGURATION);
        assertKind(404, JiraSourceException.Kind.CONFIGURATION);
        assertKind(302, JiraSourceException.Kind.CONFIGURATION);
        assertKind(503, JiraSourceException.Kind.TEMPORARY);
    }

    @Test
    void redirect는_따라가지_않는다() {
        failSearchWith(new Response(302, "", Map.of("Location", baseUrl + "/elsewhere")));

        assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                .isInstanceOf(JiraSourceException.class);
        assertThat(requests).noneMatch(request -> request.path().equals("/elsewhere"));
    }

    @Test
    void 인증_정보가_없거나_형식이_틀리면_호출하지_않고_값을_메시지에_싣지_않는다() {
        for (String credential : List.of("", "only-token-without-email", "svc@example.com:", ":token")) {
            environment = Map.of("JIRA_DEMO_CREDENTIAL", credential);

            assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                    .isInstanceOfSatisfying(JiraSourceException.class, error -> {
                        assertThat(error.kind()).isEqualTo(JiraSourceException.Kind.CONFIGURATION);
                        if (!credential.isEmpty()) {
                            assertThat(error.getMessage()).doesNotContain(credential);
                        }
                    });
        }
        environment = Map.of();
        assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                .isInstanceOf(JiraSourceException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void 숫자가_아닌_프로젝트_ID는_JQL에_넣지_않는다() {
        assertThatThrownBy(() -> source().fetch(target("10000 OR project is not EMPTY"), Instant.EPOCH, null))
                .isInstanceOfSatisfying(JiraSourceException.class,
                        error -> assertThat(error.kind()).isEqualTo(JiraSourceException.Kind.CONFIGURATION));
        assertThat(requests).isEmpty();
    }

    @Test
    void https가_아닌_외부_주소로는_인증_헤더를_보내지_않는다() {
        JiraSyncTarget insecure = new JiraSyncTarget(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "10000", "PAY", "http://example.atlassian.net", "JIRA_DEMO_CREDENTIAL", null);

        assertThatThrownBy(() -> source().fetch(insecure, Instant.EPOCH, null))
                .isInstanceOfSatisfying(JiraSourceException.class,
                        error -> assertThat(error.kind()).isEqualTo(JiraSourceException.Kind.CONFIGURATION));
    }

    @Test
    void 계정_시간대를_알_수_없으면_추측하지_않고_멈춘다() {
        responder = request -> request.path().equals("/rest/api/3/myself")
                ? new Response(200, "{\"accountId\":\"svc\"}", Map.of())
                : Response.ok("search-page-1.json");

        assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                .isInstanceOfSatisfying(JiraSourceException.class,
                        error -> assertThat(error.kind()).isEqualTo(JiraSourceException.Kind.CONFIGURATION));
        assertThat(requests).noneMatch(request -> request.path().equals(JiraCloudIssueSource.SEARCH_PATH));
    }

    private void assertKind(int status, JiraSourceException.Kind kind) {
        failSearchWith(new Response(status, "{}", Map.of()));
        assertThatThrownBy(() -> source().fetch(target("10000"), Instant.EPOCH, null))
                .isInstanceOfSatisfying(JiraSourceException.class, error -> assertThat(error.kind()).isEqualTo(kind));
    }

    private void failSearchWith(Response failure) {
        responder = request -> request.path().equals("/rest/api/3/myself") ? Response.ok("myself.json") : failure;
    }

    private JiraCloudIssueSource source() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new JiraCloudIssueSource(client, new ObjectMapper(), new JiraProperties(true, Duration.ofSeconds(5), 100),
                name -> environment.get(name), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private JiraSyncTarget target(String projectId) {
        return new JiraSyncTarget(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), projectId, "PAY",
                baseUrl, "JIRA_DEMO_CREDENTIAL", null);
    }

    private Request only(String path) {
        List<Request> matched = requests.stream().filter(request -> request.path().equals(path)).toList();
        assertThat(matched).hasSize(1);
        return matched.getFirst();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> query = rawQuery == null ? Map.of() : java.util.Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> pair.length > 1 ? decode(pair[1]) : ""));
        Request request = new Request(exchange.getRequestURI().getPath(), query,
                exchange.getRequestHeaders().getFirst("Authorization"));
        requests.add(request);
        Response response = responder.apply(request);
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record Request(String path, Map<String, String> query, String authorization) {
    }

    private record Response(int status, String body, Map<String, String> headers) {

        static Response ok(String fixture) {
            try (InputStream in = JiraCloudIssueSourceTest.class.getResourceAsStream("/jira/" + fixture)) {
                return new Response(200, new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.of());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
