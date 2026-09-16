package com.knowledgelink.job.application;

import com.knowledgelink.job.domain.JobKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 새 작업. scopeId는 SYNC·RECONCILE의 대상 scope, targetId는 그 밖의 대상(분석·질문·자료 ID)이다.
 * requestedBy는 작업을 요청한 계정이며, 시스템이 만든 작업은 null이다(ADMIN만 조회한다).
 * snapshotJson은 실행에 필요한 입력(JSON 객체)이며 비밀값을 넣지 않는다.
 */
public record NewJob(UUID workspaceId, JobKind kind, UUID scopeId, UUID targetId, UUID requestedBy,
                     String snapshotJson) {

    public NewJob {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(kind, "kind");
        if (kind.requiresScope() && scopeId == null) {
            throw new IllegalArgumentException(kind + " 작업에는 scope가 필요합니다.");
        }
        snapshotJson = snapshotJson == null || snapshotJson.isBlank() ? "{}" : snapshotJson;
    }

    /** 동기화·정합성 점검처럼 시스템이 scope에 대해 만드는 작업. */
    public static NewJob forScope(UUID workspaceId, JobKind kind, UUID scopeId) {
        return new NewJob(workspaceId, kind, scopeId, null, null, null);
    }

    /** 사용자가 요청한 작업(분석·질문·요약 카드). */
    public static NewJob requestedBy(UUID workspaceId, JobKind kind, UUID targetId, UUID requesterId) {
        return new NewJob(workspaceId, kind, null, targetId, Objects.requireNonNull(requesterId, "requesterId"), null);
    }

    /** 시스템이 다른 작업의 뒤를 이어 만드는 작업(색인·연결 추정). */
    public static NewJob forTarget(UUID workspaceId, JobKind kind, UUID targetId) {
        return new NewJob(workspaceId, kind, null, targetId, null, null);
    }
}
