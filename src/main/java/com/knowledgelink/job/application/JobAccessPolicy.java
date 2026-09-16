package com.knowledgelink.job.application;

import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.job.domain.JobStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 작업 조회·재시도 권한(명세 04 2장). 요청한 본인과 같은 조직 ADMIN만 볼 수 있고,
 * 요청자가 없는 시스템 작업(동기화·정합성 점검·색인·연결 추정)은 ADMIN만 본다.
 *
 * <p>볼 수 없는 작업은 403이 아니라 없는 작업과 같은 404다. 403은 그 ID의 작업이 있다는 사실을 드러낸다.
 */
@Service
public class JobAccessPolicy {

    /** 사용량이 불명인 작업은 공급자 내역과 대조하기 전에는 수동 재시도도 막는다(명세 04 4장). */
    public static final String USAGE_UNKNOWN = "PROVIDER_USAGE_UNKNOWN";

    /** 볼 수 있으면 그대로 돌려주고, 없거나 볼 수 없으면 404. */
    public JobView requireVisible(AccountPrincipal principal, Optional<JobView> job) {
        return job.filter(found -> isVisible(principal, found))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 실패한 작업만 다시 시도할 수 있다. 사용량 불명은 제외한다. */
    public boolean canRetry(JobView job) {
        return job.status() == JobStatus.FAILED && !USAGE_UNKNOWN.equals(job.errorCode());
    }

    public void requireRetryable(JobView job) {
        if (!canRetry(job)) {
            throw new ApiException(ErrorCode.INVALID_STATE);
        }
    }

    private boolean isVisible(AccountPrincipal principal, JobView job) {
        if (!job.workspaceId().equals(principal.workspaceId())) {
            return false;
        }
        return principal.isAdmin() || principal.accountId().equals(job.requestedBy());
    }
}
