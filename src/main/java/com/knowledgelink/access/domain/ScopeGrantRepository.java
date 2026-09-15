package com.knowledgelink.access.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScopeGrantRepository extends JpaRepository<ScopeGrant, UUID> {

    boolean existsByAccountIdAndScopeId(UUID accountId, UUID scopeId);

    @Query("""
            select g.scopeId from ScopeGrant g join SourceScope s on s.id = g.scopeId
            where g.accountId = :accountId and s.workspaceId = :workspaceId and s.enabled = true
            """)
    List<UUID> findEnabledScopeIdsGrantedTo(@Param("accountId") UUID accountId,
                                            @Param("workspaceId") UUID workspaceId);
}
