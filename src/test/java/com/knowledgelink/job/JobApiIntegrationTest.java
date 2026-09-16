package com.knowledgelink.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.job.application.JobAccessPolicy;
import com.knowledgelink.job.application.JobLease;
import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.application.NewJob;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import com.knowledgelink.job.domain.SlotKey;
import com.knowledgelink.job.persistence.JobEngine;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.ApiSession;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** GET /jobs/{jobId}와 POST /jobs/{jobId}/retry. 권한 밖 작업은 없는 작업과 같은 404다. */
@IntegrationTest
class JobApiIntegrationTest {

    private static final String PASSWORD = "ready-password-123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JobEngine engine;

    @Autowired
    TestFixtures fixtures;

    private Workspace orgA;
    private Account memberA;
    private Account otherMemberA;
    private SourceScope pay;
    private ApiSession member;
    private ApiSession admin;

    @BeforeEach
    void setUp() throws Exception {
        fixtures.reset();
        orgA = fixtures.workspace("Org A");
        fixtures.readyAccount(orgA, "admin.a", AccountRole.ADMIN, PASSWORD);
        memberA = fixtures.readyAccount(orgA, "member.a", AccountRole.MEMBER, PASSWORD);
        otherMemberA = fixtures.readyAccount(orgA, "member.b", AccountRole.MEMBER, PASSWORD);
        pay = fixtures.scope(fixtures.jiraConnection(orgA, "a-jira", "site-a"), "10000", "PAY");

        member = new ApiSession(mockMvc);
        member.login("member.a", PASSWORD).andExpect(status().isOk());
        admin = new ApiSession(mockMvc);
        admin.login("admin.a", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void 요청한_본인은_작업_상태를_보고_ADMIN도_본다() throws Exception {
        UUID analysisId = UUID.randomUUID();
        UUID jobId = engine.enqueue(NewJob.requestedBy(orgA.getId(), JobKind.ANALYSIS, analysisId, memberA.getId()));

        member.get("/api/v1/jobs/" + jobId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.kind").value("ANALYSIS"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.canRetry").value(false))
                .andExpect(jsonPath("$.resourceId").value(analysisId.toString()));
        admin.get("/api/v1/jobs/" + jobId).andExpect(status().isOk());
    }

    @Test
    void 응답에_실행기_소유_정보는_없다() throws Exception {
        UUID jobId = engine.enqueue(
                NewJob.requestedBy(orgA.getId(), JobKind.QUESTION, UUID.randomUUID(), memberA.getId()));
        engine.claim(SlotKey.AI, EnumSet.of(JobKind.QUESTION), "worker-1").orElseThrow();

        member.get("/api/v1/jobs/" + jobId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.runToken").doesNotExist())
                .andExpect(jsonPath("$.leaseExpiresAt").doesNotExist());
    }

    @Test
    void 남의_작업과_시스템_작업과_다른_조직_작업은_모두_404다() throws Exception {
        UUID othersJob = engine.enqueue(
                NewJob.requestedBy(orgA.getId(), JobKind.ANALYSIS, UUID.randomUUID(), otherMemberA.getId()));
        UUID systemJob = engine.enqueue(NewJob.forScope(orgA.getId(), JobKind.SYNC, pay.getId()));
        Workspace orgB = fixtures.workspace("Org B");
        SourceScope otherScope = fixtures.scope(fixtures.jiraConnection(orgB, "b-jira", "site-b"), "20000", "BPAY");
        UUID otherOrgJob = engine.enqueue(NewJob.forScope(orgB.getId(), JobKind.SYNC, otherScope.getId()));

        member.get("/api/v1/jobs/" + othersJob)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        member.get("/api/v1/jobs/" + systemJob).andExpect(status().isNotFound());
        member.get("/api/v1/jobs/" + UUID.randomUUID()).andExpect(status().isNotFound());
        admin.get("/api/v1/jobs/" + otherOrgJob).andExpect(status().isNotFound());

        admin.get("/api/v1/jobs/" + systemJob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeId").value(pay.getId().toString()));
    }

    @Test
    void 실패한_작업은_같은_jobId로_다시_큐에_들어간다() throws Exception {
        UUID jobId = engine.enqueue(
                NewJob.requestedBy(orgA.getId(), JobKind.ANALYSIS, UUID.randomUUID(), memberA.getId()));
        failJob(SlotKey.AI, EnumSet.of(JobKind.ANALYSIS), "AI_OUTPUT_INVALID");

        member.get("/api/v1/jobs/" + jobId)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("AI_OUTPUT_INVALID"))
                .andExpect(jsonPath("$.canRetry").value(true));

        member.postJson("/api/v1/jobs/" + jobId + "/retry", "")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        JobView job = engine.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attemptCount()).as("누적 시도는 유지한다").isEqualTo(1);
        assertThat(job.monthKey()).isNotBlank();
    }

    @Test
    void 실패하지_않은_작업과_사용량_불명_작업의_재시도는_거절한다() throws Exception {
        // AI 슬롯은 하나뿐이라 실패로 끝내 슬롯을 반납한 뒤에 다음 작업을 선점한다.
        UUID unknownUsage = engine.enqueue(
                NewJob.requestedBy(orgA.getId(), JobKind.QUESTION, UUID.randomUUID(), memberA.getId()));
        failJob(SlotKey.AI, EnumSet.of(JobKind.QUESTION), JobAccessPolicy.USAGE_UNKNOWN);

        member.get("/api/v1/jobs/" + unknownUsage).andExpect(jsonPath("$.canRetry").value(false));
        member.postJson("/api/v1/jobs/" + unknownUsage + "/retry", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        UUID running = engine.enqueue(
                NewJob.requestedBy(orgA.getId(), JobKind.ANALYSIS, UUID.randomUUID(), memberA.getId()));
        engine.claim(SlotKey.AI, EnumSet.of(JobKind.ANALYSIS), "worker-1").orElseThrow();

        member.postJson("/api/v1/jobs/" + running + "/retry", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 같은_scope에_활성_동기화가_있으면_실패한_동기화를_다시_시도할_수_없다() throws Exception {
        UUID failedSync = engine.enqueue(NewJob.forScope(orgA.getId(), JobKind.SYNC, pay.getId()));
        failJob(SlotKey.SYNC, EnumSet.of(JobKind.SYNC), "SOURCE_UNAVAILABLE");
        UUID activeSync = engine.enqueue(NewJob.forScope(orgA.getId(), JobKind.SYNC, pay.getId()));

        assertThat(activeSync).isNotEqualTo(failedSync);
        admin.postJson("/api/v1/jobs/" + failedSync + "/retry", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_SYNC_EXISTS"));
        assertThat(engine.find(failedSync).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
    }

    private void failJob(SlotKey slot, EnumSet<JobKind> kinds, String errorCode) {
        JobLease lease = engine.claim(slot, kinds, "worker-1").orElseThrow();
        engine.fail(lease, errorCode);
    }
}
