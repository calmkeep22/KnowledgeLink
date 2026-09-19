package com.knowledgelink.demo.infrastructure.live;

import com.knowledgelink.demo.application.PastWorkSource;
import com.knowledgelink.demo.application.SimilarWorkService;
import com.knowledgelink.demo.domain.ActivityKind;
import com.knowledgelink.demo.domain.DemoActivity;
import com.knowledgelink.demo.domain.PastWorkSourceInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 공개 Jira·GitHub에서 해결된 이슈와 이를 고친 병합 PR을 가져와 과거 업무로 쓴다.
 *
 * <p>병합 PR 제목의 Jira 키(예: {@code KAFKA-12345:})로 이슈를 찾으므로, 모든 이슈에는 이를 고친 PR이 하나 이상 연결된다.
 * 처음 검색할 때 한 번 수집하고, 성공하면 저장본을 남긴다. 저장본이 {@code snapshotMaxAge}보다 새로우면 수집하지 않고
 * 저장본으로 바로 시작한다(재시작마다 외부 API를 기다리지 않기 위해서다). 수집에 실패하면 저장본, 저장본도 없으면 가명 예시 데이터를 쓴다.
 */
@Slf4j
public final class LivePastWorkSource implements PastWorkSource {

    private final GitHubPullRequestClient github;
    private final ApacheJiraClient jira;
    private final LivePastWorkProperties.Apache properties;
    private final ObjectMapper objectMapper;
    private final PastWorkSource fallback;
    private final Clock clock;
    private final Pattern issueKey;

    private volatile Loaded loaded;

    record Snapshot(String label, Instant fetchedAt, List<DemoActivity> items, Map<String, List<String>> links) {
        Snapshot {
            items = items == null ? List.of() : List.copyOf(items);
            links = links == null ? Map.of() : Map.copyOf(links);
        }
    }

    private record Loaded(Snapshot snapshot, String sourceType) {
    }

    LivePastWorkSource(GitHubPullRequestClient github, ApacheJiraClient jira, LivePastWorkProperties.Apache properties,
                       ObjectMapper objectMapper, PastWorkSource fallback, Clock clock) {
        this.github = github;
        this.jira = jira;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.clock = clock;
        this.issueKey = Pattern.compile("\\b" + properties.projectKey() + "-\\d{1,7}\\b");
    }

    @Override
    public List<DemoActivity> findAll() {
        return load().snapshot().items();
    }

    @Override
    public Map<String, List<String>> links() {
        return load().snapshot().links();
    }

    @Override
    public PastWorkSourceInfo info() {
        Loaded current = load();
        Snapshot snapshot = current.snapshot();
        if ("mock".equals(current.sourceType())) {
            return fallback.info();
        }
        return PastWorkSourceInfo.of(snapshot.label(), current.sourceType(), snapshot.fetchedAt(),
                snapshot.items(), snapshot.links(), properties.examples());
    }

    private Loaded load() {
        Loaded current = loaded;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (loaded == null) {
                loaded = fetchOrFallback();
            }
            return loaded;
        }
    }

    private Loaded fetchOrFallback() {
        Snapshot saved = readSnapshot();
        if (saved != null && isFresh(saved)) {
            log.info("Past work loaded from fresh snapshot fetched at {}", saved.fetchedAt());
            return new Loaded(saved, "snapshot");
        }
        try {
            Snapshot fetched = fetch();
            saveSnapshot(fetched);
            log.info("Past work fetched from {}: issues={}, pullRequests={}", fetched.label(),
                    fetched.items().stream().filter(item -> item.kind() == ActivityKind.JIRA_ISSUE).count(),
                    fetched.items().stream().filter(item -> item.kind() == ActivityKind.GITHUB_PULL_REQUEST).count());
            return new Loaded(fetched, "live");
        } catch (RuntimeException exception) {
            log.warn("Past work fetch failed; trying snapshot", exception);
        }
        if (saved != null) {
            log.info("Past work loaded from stale snapshot fetched at {}", saved.fetchedAt());
            return new Loaded(saved, "snapshot");
        }
        log.warn("No past work snapshot; using bundled example data");
        return new Loaded(new Snapshot("가명 예시 데이터(수집 실패)", null, fallback.findAll(), fallback.links()), "mock");
    }

    private boolean isFresh(Snapshot snapshot) {
        return snapshot.fetchedAt() != null
                && !properties.snapshotMaxAge().isZero()
                && snapshot.fetchedAt().plus(properties.snapshotMaxAge()).isAfter(clock.instant());
    }

    Snapshot fetch() {
        List<GitHubPullRequestClient.MergedPullRequest> pullRequests = github.fetchMerged(properties.pullRequestPages());
        Map<String, List<GitHubPullRequestClient.MergedPullRequest>> pullRequestsByKey = new LinkedHashMap<>();
        for (GitHubPullRequestClient.MergedPullRequest pullRequest : pullRequests) {
            for (String key : issueKeys(pullRequest.title())) {
                pullRequestsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(pullRequest);
            }
        }
        if (pullRequestsByKey.isEmpty()) {
            throw new LiveSourceException("Jira 키가 붙은 병합 PR이 없습니다.");
        }

        List<ApacheJiraClient.JiraIssue> issues = jira.fetchResolved(pullRequestsByKey.keySet(), properties.issueTypes())
                .stream()
                .sorted(Comparator.comparing(ApacheJiraClient.JiraIssue::resolvedAt).reversed())
                .limit(properties.maxIssues())
                .toList();
        if (issues.isEmpty()) {
            throw new LiveSourceException("연결할 해결된 Jira 이슈가 없습니다.");
        }

        Map<String, DemoActivity> items = new LinkedHashMap<>();
        Map<String, Set<String>> links = new LinkedHashMap<>();
        for (ApacheJiraClient.JiraIssue issue : issues) {
            items.put(issue.key(), toActivity(issue));
            for (GitHubPullRequestClient.MergedPullRequest pullRequest : pullRequestsByKey.getOrDefault(issue.key(), List.of())) {
                DemoActivity activity = toActivity(pullRequest);
                items.putIfAbsent(activity.id(), activity);
                links.computeIfAbsent(issue.key(), ignored -> new LinkedHashSet<>()).add(activity.id());
                links.computeIfAbsent(activity.id(), ignored -> new LinkedHashSet<>()).add(issue.key());
            }
        }
        Map<String, List<String>> linkLists = new LinkedHashMap<>();
        links.forEach((id, linked) -> linkLists.put(id, List.copyOf(linked)));
        return new Snapshot(properties.projectName() + " 공개 Jira·GitHub", clock.instant(),
                List.copyOf(items.values()), linkLists);
    }

    private List<String> issueKeys(String title) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = issueKey.matcher(title == null ? "" : title);
        while (matcher.find()) {
            keys.add(matcher.group());
        }
        return keys;
    }

    private DemoActivity toActivity(ApacheJiraClient.JiraIssue issue) {
        boolean assigned = !issue.assigneeId().isBlank();
        return new DemoActivity(
                issue.key(),
                properties.projectKey().toLowerCase(Locale.ROOT),
                assigned ? "jira:" + issue.assigneeId() : SimilarWorkService.UNASSIGNED_MEMBER_ID,
                assigned && !issue.assigneeName().isBlank() ? issue.assigneeName() : "담당자 없음",
                ActivityKind.JIRA_ISSUE,
                issue.summary().isBlank() ? issue.key() : issue.summary(),
                (issue.status().isBlank() ? "RESOLVED" : issue.status()).toUpperCase(Locale.ROOT),
                issue.resolvedAt(),
                properties.jiraBase() + "/browse/" + issue.key(),
                SourceText.jira(issue.description(), properties.detailsMaxLength()));
    }

    private DemoActivity toActivity(GitHubPullRequestClient.MergedPullRequest pullRequest) {
        return new DemoActivity(
                "PR-" + pullRequest.number(),
                properties.projectKey().toLowerCase(Locale.ROOT),
                "github:" + pullRequest.author(),
                pullRequest.author(),
                ActivityKind.GITHUB_PULL_REQUEST,
                pullRequest.title().isBlank() ? "PR #" + pullRequest.number() : pullRequest.title(),
                "MERGED",
                pullRequest.mergedAt(),
                pullRequest.url().isBlank()
                        ? "https://github.com/" + properties.githubRepository() + "/pull/" + pullRequest.number()
                        : pullRequest.url(),
                SourceText.pullRequest(pullRequest.body(), properties.detailsMaxLength()));
    }

    private void saveSnapshot(Snapshot snapshot) {
        if (properties.snapshotPath().isEmpty()) {
            return;
        }
        Path target = Path.of(properties.snapshotPath());
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), snapshot);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not save past work snapshot to {}", target, exception);
        }
    }

    private Snapshot readSnapshot() {
        if (properties.snapshotPath().isEmpty()) {
            return null;
        }
        Path source = Path.of(properties.snapshotPath());
        if (!Files.isRegularFile(source)) {
            return null;
        }
        try {
            Snapshot snapshot = objectMapper.readValue(source.toFile(), Snapshot.class);
            return snapshot.items() == null || snapshot.items().isEmpty() ? null : snapshot;
        } catch (RuntimeException exception) {
            log.warn("Could not read past work snapshot from {}", source, exception);
            return null;
        }
    }
}
