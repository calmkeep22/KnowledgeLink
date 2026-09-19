package com.knowledgelink.demo.infrastructure.live;

import com.knowledgelink.demo.domain.PastWorkSourceInfo.ExampleQuery;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 유사 업무 검색 대상의 출처 설정. {@code source=apache}이면 공개 Apache Jira와 GitHub 저장소에서 해결된 이슈와
 * 이를 고친 병합 PR을 가져온다. 대상 주소는 운영자 설정으로만 정하며 사용자 입력으로 바꾸지 않는다.
 */
@ConfigurationProperties("kl.demo.past-work")
public record LivePastWorkProperties(
        @DefaultValue("mock") String source,
        List<ExampleQuery> mockExamples,
        @DefaultValue Apache apache
) {
    public LivePastWorkProperties {
        mockExamples = mockExamples == null ? List.of() : List.copyOf(mockExamples);
    }

    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9]{1,19}");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    public record Apache(
            @DefaultValue("https://issues.apache.org/jira") URI jiraBaseUrl,
            @DefaultValue("KAFKA") String projectKey,
            @DefaultValue("Apache Kafka") String projectName,
            @DefaultValue("apache/kafka") String githubRepository,
            @DefaultValue("") String githubToken,
            @DefaultValue("10") int pullRequestPages,
            @DefaultValue("200") int maxIssues,
            @DefaultValue("Bug") List<String> issueTypes,
            @DefaultValue("") String snapshotPath,
            @DefaultValue("20s") Duration timeout,
            @DefaultValue("800") int detailsMaxLength,
            List<ExampleQuery> examples
    ) {
        public Apache {
            if (jiraBaseUrl == null || !"https".equalsIgnoreCase(jiraBaseUrl.getScheme())) {
                throw new IllegalArgumentException("Jira 주소는 HTTPS여야 합니다.");
            }
            if (projectKey == null || !PROJECT_KEY.matcher(projectKey).matches()) {
                throw new IllegalArgumentException("Jira 프로젝트 키 형식이 올바르지 않습니다.");
            }
            if (githubRepository == null || !REPOSITORY.matcher(githubRepository).matches()) {
                throw new IllegalArgumentException("GitHub 저장소는 owner/repo 형식이어야 합니다.");
            }
            if (pullRequestPages < 1 || pullRequestPages > 30) {
                throw new IllegalArgumentException("pullRequestPages는 1 이상 30 이하여야 합니다.");
            }
            if (maxIssues < 1 || maxIssues > 1000) {
                throw new IllegalArgumentException("maxIssues는 1 이상 1000 이하여야 합니다.");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
            }
            if (detailsMaxLength < 100) {
                throw new IllegalArgumentException("detailsMaxLength는 100 이상이어야 합니다.");
            }
            issueTypes = issueTypes == null ? List.of() : List.copyOf(issueTypes);
            projectName = projectName == null || projectName.isBlank() ? projectKey : projectName;
            githubToken = githubToken == null ? "" : githubToken.strip();
            snapshotPath = snapshotPath == null ? "" : snapshotPath.strip();
            examples = examples == null ? List.of() : List.copyOf(examples);
        }

        String jiraBase() {
            String value = jiraBaseUrl.toString();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }
}
