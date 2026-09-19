package com.knowledgelink.source.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.NewJob;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.persistence.JobEngine;
import com.knowledgelink.job.worker.JobHandlers;
import com.knowledgelink.job.worker.JobMetrics;
import com.knowledgelink.job.worker.JobWorker;
import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.MutableClock;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** F02/T07~T09·T12의 Jira 동기화 기반 검증. 실제 HTTP 대신 같은 outbound port의 fake를 쓴다. */
@IntegrationTest
class JiraSyncIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-18T01:00:00Z");

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JobEngine engine;

    @Autowired
    JobProperties properties;

    @Autowired
    JiraSyncStore store;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();
    private Workspace workspace;
    private SourceConnection connection;
    private SourceScope scope;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        clock.set(T0);
        workspace = fixtures.workspace("Jira Sync Org");
        connection = fixtures.jiraConnection(workspace, "jira", "site-1");
        scope = fixtures.scope(connection, "10000", "PAY");
    }

    @AfterEach
    void tearDown() {
        schedulers.forEach(ScheduledExecutorService::shutdownNow);
    }

    @Test
    void 모든_페이지를_저장하고_재실행해도_중복이_생기지_않는다() {
        FakeJiraSource source = new FakeJiraSource(issue("1", "PAY-1", T0.minusSeconds(60)),
                issue("2", "PAY-2", T0));

        runSync(source);

        assertThat(fixtures.count("work_item")).isEqualTo(2);
        assertThat(cursorExternalId()).isEqualTo("2");
        assertThat(fixtures.lastSyncedAt(scope)).isEqualTo(T0);

        clock.advance(Duration.ofMinutes(1));
        runSync(source);

        assertThat(fixtures.count("work_item")).isEqualTo(2);
        assertThat(source.updatedFrom()).hasSize(4);
        assertThat(source.updatedFrom().get(2)).isEqualTo(T0.minus(JiraCursor.OVERLAP));
    }

    @Test
    void 페이지_저장_뒤_요청_제한이_나도_cursor부터_재개해_누락과_중복이_없다() {
        FakeJiraSource source = new FakeJiraSource(issue("1", "PAY-1", T0.minusSeconds(60)),
                issue("2", "PAY-2", T0));
        source.rateLimitSecondPageOnce();
        UUID jobId = enqueueSync();
        JobWorker worker = worker(source);

        worker.tick();

        assertThat(engine.find(jobId).orElseThrow().status()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(engine.find(jobId).orElseThrow().errorCode()).isEqualTo(JiraSyncHandler.RATE_LIMITED);
        assertThat(fixtures.count("work_item")).isEqualTo(1);
        assertThat(cursorExternalId()).isEqualTo("1");
        assertThat(fixtures.lastSyncedAt(scope)).isNull();

        clock.advance(Duration.ofSeconds(31));
        worker.tick();

        assertThat(engine.find(jobId).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(engine.find(jobId).orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(fixtures.count("work_item")).isEqualTo(2);
        assertThat(cursorExternalId()).isEqualTo("2");
        assertThat(fixtures.lastSyncedAt(scope)).isEqualTo(T0.plusSeconds(31));
        assertThat(source.updatedFrom().get(2))
                .isEqualTo(T0.minusSeconds(60).minus(JiraCursor.OVERLAP));
    }

    @Test
    void 인증_오류는_재시도하지_않고_연결을_ERROR로_바꾼다() {
        JiraIssueSource source = (target, updatedFrom, pageToken) -> {
            throw new JiraSourceException(JiraSourceException.Kind.AUTHENTICATION, null);
        };
        UUID jobId = enqueueSync();

        worker(source).tick();

        assertThat(engine.find(jobId).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
        assertThat(engine.find(jobId).orElseThrow().errorCode())
                .isEqualTo(JiraSyncHandler.AUTHENTICATION_ERROR);
        assertThat(jdbc.queryForObject("SELECT status FROM source_connection WHERE id = ?", String.class,
                connection.getId())).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM source_connection WHERE id = ?", String.class,
                connection.getId())).isEqualTo(JiraSyncHandler.AUTHENTICATION_ERROR);
    }

    private void runSync(JiraIssueSource source) {
        UUID jobId = enqueueSync();
        worker(source).tick();
        assertThat(engine.find(jobId).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    private UUID enqueueSync() {
        return engine.enqueue(NewJob.forScope(workspace.getId(), JobKind.SYNC, scope.getId()));
    }

    private JobWorker worker(JiraIssueSource source) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.add(scheduler);
        JiraSyncHandler handler = new JiraSyncHandler(source, store);
        return new JobWorker(engine, new JobHandlers(List.of(handler)),
                new JobMetrics(new SimpleMeterRegistry()), properties, "jira-test", scheduler, Runnable::run);
    }

    private String cursorExternalId() {
        return jdbc.queryForObject("SELECT sync_cursor ->> 'externalId' FROM source_scope WHERE id = ?",
                String.class, scope.getId());
    }

    private JiraIssue issue(String id, String key, Instant updatedAt) {
        return new JiraIssue(id, scope.getExternalId(), key, "Title " + key, "Body " + key, "Task", "Open",
                null, List.of("backend"), List.of("api"),
                List.of(new JiraComment("c-" + id, "account-1", "comment", updatedAt, updatedAt)),
                null, updatedAt, connection.getBaseUrl() + "/browse/" + key);
    }

    private static final class FakeJiraSource implements JiraIssueSource {

        private final JiraIssue first;
        private final JiraIssue second;
        private final List<Instant> updatedFrom = new ArrayList<>();
        private boolean rateLimitSecondPage;

        private FakeJiraSource(JiraIssue first, JiraIssue second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public JiraPage fetch(JiraSyncTarget target, Instant updatedFrom, String pageToken) {
            this.updatedFrom.add(updatedFrom);
            if (pageToken == null) {
                return new JiraPage(List.of(first), "second");
            }
            if (rateLimitSecondPage) {
                rateLimitSecondPage = false;
                throw new JiraSourceException(JiraSourceException.Kind.RATE_LIMIT, Duration.ofSeconds(30));
            }
            return new JiraPage(List.of(second), null);
        }

        void rateLimitSecondPageOnce() {
            rateLimitSecondPage = true;
        }

        List<Instant> updatedFrom() {
            return List.copyOf(updatedFrom);
        }
    }
}
