package com.knowledgelink.source.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceScopeRepository extends JpaRepository<SourceScope, UUID> {

    Optional<SourceScope> findByConnectionIdAndExternalId(UUID connectionId, String externalId);

    List<SourceScope> findByConnectionIdAndDisplayKey(UUID connectionId, String displayKey);

    @Query("select s.id from SourceScope s where s.workspaceId = :workspaceId and s.enabled = true")
    List<UUID> findEnabledIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /** 호출하는 쪽이 권한으로 거른 ID만 넘긴다. 빈 컬렉션은 넘기지 않는다. */
    @Query("""
            select new com.knowledgelink.source.domain.ScopeSummary(s.id, c.kind, s.displayKey, s.lastSyncedAt)
            from SourceScope s join SourceConnection c on c.id = s.connectionId
            where s.id in :ids and s.enabled = true
            order by c.kind, s.displayKey
            """)
    List<ScopeSummary> findSummaries(@Param("ids") Collection<UUID> ids);
}
