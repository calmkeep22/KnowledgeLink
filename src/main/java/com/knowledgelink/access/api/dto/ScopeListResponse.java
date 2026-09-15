package com.knowledgelink.access.api.dto;

import com.knowledgelink.source.domain.ScopeKind;
import com.knowledgelink.source.domain.ScopeSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScopeListResponse(List<Item> items) {

    public record Item(UUID id, ScopeKind kind, String displayKey, Instant lastSyncedAt) {
    }

    public static ScopeListResponse from(List<ScopeSummary> scopes) {
        return new ScopeListResponse(scopes.stream()
                .map(scope -> new Item(scope.id(), scope.kind(), scope.displayKey(), scope.lastSyncedAt()))
                .toList());
    }
}
