package com.knowledgelink.job.api.dto;

import com.knowledgelink.job.application.JobView;
import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import java.util.UUID;

/**
 * 작업 상태 응답. resourceId는 분석·질문처럼 결과가 저장되는 자료의 ID이고, scopeId는 동기화 대상이다.
 * 소유자·run_token·lease처럼 내부 실행 정보는 담지 않는다.
 */
public record JobResponse(UUID jobId, JobKind kind, JobStatus status, String stage, int attemptCount,
                          String errorCode, boolean canRetry, UUID resourceId, UUID scopeId) {

    public static JobResponse of(JobView job, boolean canRetry) {
        return new JobResponse(job.id(), job.kind(), job.status(), job.stage(), job.attemptCount(),
                job.errorCode(), canRetry, job.targetId(), job.scopeId());
    }
}
