package com.knowledgelink.job.application;

import com.knowledgelink.job.domain.JobKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 새 작업. scopeId는 SYNC·RECONCILE의 대상 scope, targetId는 그 밖의 대상(분석·질문·자료 ID)이다.
 * snapshotJson은 실행에 필요한 입력(JSON 객체)이며 비밀값을 넣지 않는다.
 */
public record NewJob(UUID workspaceId, JobKind kind, UUID scopeId, UUID targetId, String snapshotJson) {

    public NewJob {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(kind, "kind");
        if (kind.requiresScope() && scopeId == null) {
            throw new IllegalArgumentException(kind + " 작업에는 scope가 필요합니다.");
        }
        snapshotJson = snapshotJson == null || snapshotJson.isBlank() ? "{}" : snapshotJson;
    }

    public static NewJob forScope(UUID workspaceId, JobKind kind, UUID scopeId) {
        return new NewJob(workspaceId, kind, scopeId, null, null);
    }

    public static NewJob forTarget(UUID workspaceId, JobKind kind, UUID targetId) {
        return new NewJob(workspaceId, kind, null, targetId, null);
    }
}
