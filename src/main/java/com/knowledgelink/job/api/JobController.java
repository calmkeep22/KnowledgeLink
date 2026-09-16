package com.knowledgelink.job.api;

import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.job.api.dto.JobAcceptedResponse;
import com.knowledgelink.job.api.dto.JobResponse;
import com.knowledgelink.job.application.JobAccessPolicy;
import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.persistence.JobEngine;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobEngine jobEngine;
    private final JobAccessPolicy accessPolicy;

    /** 진행 상태 조회. 화면은 이 응답을 2~10초 간격으로 다시 읽는다. */
    @GetMapping("/{jobId}")
    public JobResponse get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID jobId) {
        JobView job = accessPolicy.requireVisible(principal, jobEngine.find(jobId));
        return JobResponse.of(job, accessPolicy.canRetry(job));
    }

    /**
     * 실패한 작업을 다시 큐에 넣는다. 같은 jobId와 누적 시도·입장월을 유지한다.
     * 같은 scope에 이미 활성 동기화가 있으면 409다.
     */
    @PostMapping("/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobAcceptedResponse retry(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID jobId) {
        JobView job = accessPolicy.requireVisible(principal, jobEngine.find(jobId));
        accessPolicy.requireRetryable(job);
        try {
            if (!jobEngine.requeueFailed(jobId)) {
                // 조회와 재시도 사이에 상태가 바뀌었다.
                throw new ApiException(ErrorCode.INVALID_STATE);
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.ACTIVE_SYNC_EXISTS);
        }
        return new JobAcceptedResponse(jobId);
    }
}
