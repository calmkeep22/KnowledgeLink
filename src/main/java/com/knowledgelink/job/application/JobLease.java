package com.knowledgelink.job.application;

import com.knowledgelink.job.domain.JobKind;
import com.knowledgelink.job.domain.SlotKey;
import java.time.Instant;
import java.util.UUID;

/**
 * 선점한 작업의 실행 권한. (jobId, slot, runToken)이 소유권이며, 이 lease로 하는 모든 쓰기는
 * 작업과 슬롯 양쪽의 run_token과 유효한 lease를 DB에서 다시 확인한다. leaseExpiresAt은 선점 당시 값이다.
 */
public record JobLease(UUID jobId, UUID workspaceId, JobKind kind, SlotKey slot, UUID scopeId, UUID targetId,
                       String stage, int attemptCount, String snapshotJson, String ownerId, UUID runToken,
                       Instant leaseExpiresAt) {
}
