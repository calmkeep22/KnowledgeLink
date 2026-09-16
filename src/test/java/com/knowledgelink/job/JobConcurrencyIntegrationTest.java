package com.knowledgelink.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.JobProperties;
import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.application.NewJob;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import com.knowledgelink.job.persistence.JobEngine;
import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.MutableClock;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 실행 슬롯 동시성(T32). 스레드마다 별도 transaction(= 별도 DB connection)으로 동시에 선점·복구한다.
 */
@IntegrationTest
class JobConcurrencyIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-15T01:00:00Z");
    private static final Set<JobKind> AI_KINDS =
            EnumSet.of(JobKind.ANALYSIS, JobKind.QUESTION, JobKind.CARD, JobKind.LINK_SUGGEST);
    private static final Set<JobKind> SYNC_KINDS = EnumSet.of(JobKind.SYNC, JobKind.RECONCILE);
    private static final int THREADS = 8;

    @Autowired
    JobEngine engine;

    @Autowired
    JobProperties properties;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    MutableClock clock;

    private Workspace org;
    private SourceConnection jira;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        clock.set(T0);
        org = fixtures.workspace("Concurrency Org");
        jira = fixtures.jiraConnection(org, "jira", "site-1");
    }

    @Test
    void T32_AI_작업을_여러_connection에서_동시에_선점해도_AI_슬롯에서_하나만_RUNNING이다() throws Exception {
        for (JobKind kind : AI_KINDS) {
            engine.enqueue(NewJob.forTarget(org.getId(), kind, UUID.randomUUID()));
            engine.enqueue(NewJob.forTarget(org.getId(), kind, UUID.randomUUID()));
        }

        List<JobLease> claimed = claimConcurrently(SlotKey.AI, AI_KINDS);

        assertThat(claimed).hasSize(1);
        assertThat(fixtures.countJobs(JobStatus.RUNNING)).isEqualTo(1);
        assertThat(fixtures.countJobs(JobStatus.QUEUED)).isEqualTo(7);
        assertThat(fixtures.count("job WHERE attempt_count > 0")).as("슬롯을 못 잡은 선점은 시도를 쓰지 않는다").isEqualTo(1);
        assertThat(fixtures.slot(SlotKey.AI)).containsEntry("job_id", claimed.getFirst().jobId());
    }

    @Test
    void T32_scope와_조직이_달라도_SYNC와_RECONCILE은_서버_전체에서_하나만_실행된다() throws Exception {
        SourceScope pay = fixtures.scope(jira, "10000", "PAY");
        SourceScope ord = fixtures.scope(jira, "10001", "ORD");
        Workspace other = fixtures.workspace("Other Org");
        SourceScope otherScope = fixtures.scope(fixtures.githubConnection(other, "github"), "555", "other/repo");
        engine.enqueue(NewJob.forScope(org.getId(), JobKind.SYNC, pay.getId()));
        engine.enqueue(NewJob.forScope(org.getId(), JobKind.RECONCILE, ord.getId()));
        engine.enqueue(NewJob.forScope(other.getId(), JobKind.SYNC, otherScope.getId()));

        List<JobLease> claimed = claimConcurrently(SlotKey.SYNC, SYNC_KINDS);

        assertThat(claimed).hasSize(1);
        assertThat(fixtures.countJobs(JobStatus.RUNNING)).isEqualTo(1);
        assertThat(fixtures.countJobs(JobStatus.QUEUED)).isEqualTo(2);
    }

    @Test
    void T32_INDEX와_AI는_동시에_실행된다() throws Exception {
        engine.enqueue(NewJob.forTarget(org.getId(), JobKind.INDEX, UUID.randomUUID()));
        engine.enqueue(NewJob.forTarget(org.getId(), JobKind.ANALYSIS, UUID.randomUUID()));

        List<Optional<JobLease>> claimed = runConcurrently(2, index -> index == 0
                ? engine.claim(SlotKey.INDEX, EnumSet.of(JobKind.INDEX), "index-worker")
                : engine.claim(SlotKey.AI, AI_KINDS, "ai-worker"));

        assertThat(claimed).allMatch(Optional::isPresent);
        assertThat(fixtures.countJobs(JobStatus.RUNNING)).isEqualTo(2);
    }

    @Test
    void T32_실행기가_죽고_여러_실행기가_동시에_복구해도_슬롯이_새지_않는다() throws Exception {
        UUID id = engine.enqueue(NewJob.forTarget(org.getId(), JobKind.ANALYSIS, UUID.randomUUID()));

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            assertThat(claimConcurrently(SlotKey.AI, AI_KINDS)).hasSize(1);

            // 선점한 실행기가 죽었다. lease가 끝난 뒤 모든 실행기가 동시에 복구를 시도한다.
            clock.advance(properties.lease().plusSeconds(1));
            List<Integer> recovered = runConcurrently(THREADS, index -> engine.recoverExpired());

            assertThat(recovered.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertThat(fixtures.slot(SlotKey.AI).get("job_id")).isNull();
            clock.advance(properties.retryMax());
            engine.promoteDueRetries();
        }

        JobView job = engine.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.attemptCount()).isEqualTo(properties.maxAttempts());
        assertThat(job.errorCode()).isEqualTo(JobEngine.LEASE_EXPIRED);
        assertThat(fixtures.countJobs(JobStatus.RUNNING)).isZero();
        assertThat(fixtures.count("execution_slot WHERE job_id IS NOT NULL")).isZero();
    }

    private List<JobLease> claimConcurrently(SlotKey slot, Set<JobKind> kinds) throws Exception {
        return runConcurrently(THREADS, index -> engine.claim(slot, kinds, "worker-" + index)).stream()
                .flatMap(Optional::stream)
                .toList();
    }

    /** threads개 스레드가 동시에 출발해 task를 한 번씩 실행한다. */
    private static <T> List<T> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.apply(index);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
