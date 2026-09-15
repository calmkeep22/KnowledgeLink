package com.knowledgelink.source.domain;

import java.time.Instant;
import java.util.UUID;

/** scope 목록 조회용 읽기 모델. 연결의 인증 정보·URL은 담지 않는다. */
public record ScopeSummary(UUID id, SourceKind sourceKind, String displayKey, Instant lastSyncedAt) {

    public ScopeKind kind() {
        return sourceKind.scopeKind();
    }
}
