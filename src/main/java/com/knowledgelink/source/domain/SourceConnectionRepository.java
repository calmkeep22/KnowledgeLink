package com.knowledgelink.source.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceConnectionRepository extends JpaRepository<SourceConnection, UUID> {

    Optional<SourceConnection> findByWorkspaceIdAndName(UUID workspaceId, String name);
}
