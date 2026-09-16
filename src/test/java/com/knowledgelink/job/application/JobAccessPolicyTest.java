package com.knowledgelink.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobAccessPolicyTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();

    private final JobAccessPolicy policy = new JobAccessPolicy();
    private final AccountPrincipal member = principal(ORG, AccountRole.MEMBER);
    private final AccountPrincipal admin = principal(ORG, AccountRole.ADMIN);

    @Test
    void 요청한_본인과_ADMIN만_본다() {
        JobView job = job(ORG, member.accountId(), JobStatus.RUNNING, null);

        assertThat(policy.requireVisible(member, Optional.of(job))).isEqualTo(job);
        assertThat(policy.requireVisible(admin, Optional.of(job))).isEqualTo(job);
        assertThatThrownBy(() -> policy.requireVisible(principal(ORG, AccountRole.MEMBER), Optional.of(job)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 요청자가_없는_시스템_작업은_ADMIN만_본다() {
        JobView systemJob = job(ORG, null, JobStatus.QUEUED, null);

        assertThat(policy.requireVisible(admin, Optional.of(systemJob))).isEqualTo(systemJob);
        assertThatThrownBy(() -> policy.requireVisible(member, Optional.of(systemJob)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 다른_조직의_작업과_없는_작업은_같은_404다() {
        JobView otherOrgJob = job(OTHER_ORG, admin.accountId(), JobStatus.SUCCEEDED, null);

        assertThatThrownBy(() -> policy.requireVisible(admin, Optional.of(otherOrgJob)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.requireVisible(admin, Optional.empty()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 실패한_작업만_다시_시도할_수_있다() {
        assertThat(policy.canRetry(job(ORG, member.accountId(), JobStatus.FAILED, "AI_TIMEOUT"))).isTrue();
        assertThat(policy.canRetry(job(ORG, member.accountId(), JobStatus.RUNNING, null))).isFalse();
        assertThat(policy.canRetry(job(ORG, member.accountId(), JobStatus.RETRY_WAIT, "AI_TIMEOUT"))).isFalse();
        assertThat(policy.canRetry(job(ORG, member.accountId(), JobStatus.SUCCEEDED, null))).isFalse();
    }

    @Test
    void 사용량이_불명인_실패는_수동_재시도도_막는다() {
        JobView unknownUsage = job(ORG, member.accountId(), JobStatus.FAILED, JobAccessPolicy.USAGE_UNKNOWN);

        assertThat(policy.canRetry(unknownUsage)).isFalse();
        assertThatThrownBy(() -> policy.requireRetryable(unknownUsage)).isInstanceOf(ApiException.class);
    }

    private static AccountPrincipal principal(UUID workspaceId, AccountRole role) {
        return new AccountPrincipal(UUID.randomUUID(), workspaceId, "user", role, false, 1);
    }

    private static JobView job(UUID workspaceId, UUID requestedBy, JobStatus status, String errorCode) {
        return new JobView(UUID.randomUUID(), workspaceId, JobKind.ANALYSIS, status, null, UUID.randomUUID(),
                requestedBy, null, 1, null, null, null, null, errorCode, "2026-09");
    }
}
