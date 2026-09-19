package com.knowledgelink.demo.infrastructure.live;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** GitHub REST API로 저장소의 최근 병합 PR을 읽는다. 토큰이 없으면 익명 호출(시간당 60회)로 동작한다. */
final class GitHubPullRequestClient {
    private static final String API = "https://api.github.com";
    private static final int PER_PAGE = 100;

    record MergedPullRequest(int number, String title, String body, String author, Instant mergedAt, String url) {
    }

    private final HttpGetter http;
    private final ObjectMapper objectMapper;
    private final String repository;
    private final String token;

    GitHubPullRequestClient(HttpGetter http, ObjectMapper objectMapper, String repository, String token) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.token = token;
    }

    List<MergedPullRequest> fetchMerged(int pages) {
        List<MergedPullRequest> merged = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            URI uri = URI.create(API + "/repos/" + repository + "/pulls?state=closed&sort=updated&direction=desc"
                    + "&per_page=" + PER_PAGE + "&page=" + page);
            List<MergedPullRequest> pageItems = parse(http.get(uri, headers()));
            merged.addAll(pageItems);
            if (pageItems.isEmpty() && page > 1) {
                break;
            }
        }
        return merged;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "KnowledgeLink-hackathon-demo");
        if (!token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    /** 닫힌 PR 목록에서 병합된 것만 남긴다. */
    List<MergedPullRequest> parse(String json) {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new LiveSourceException("GitHub PR 목록 형식이 올바르지 않습니다.");
        }
        List<MergedPullRequest> result = new ArrayList<>();
        for (JsonNode node : root) {
            String mergedAt = node.path("merged_at").asText("");
            if (mergedAt.isBlank() || node.path("merged_at").isNull()) {
                continue;
            }
            result.add(new MergedPullRequest(
                    node.path("number").asInt(),
                    node.path("title").asText(""),
                    node.path("body").isNull() ? "" : node.path("body").asText(""),
                    node.path("user").path("login").asText("unknown"),
                    Instant.parse(mergedAt),
                    node.path("html_url").asText("")));
        }
        return result;
    }
}
