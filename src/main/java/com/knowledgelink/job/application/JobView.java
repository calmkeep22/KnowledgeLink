package com.knowledgelink.job.application;

import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

/** 작업 상태 조회 결과. requestedBy가 null이면 시스템이 만든 작업이다. */
public record JobView(UUID id, UUID workspaceId, JobKind kind, JobStatus status, UUID scopeId, UUID targetId,
                      UUID requestedBy, String stage, int attemptCount, String ownerId, UUID runToken,
                      Instant leaseExpiresAt, Instant nextRunAt, String errorCode, String monthKey) {
}
