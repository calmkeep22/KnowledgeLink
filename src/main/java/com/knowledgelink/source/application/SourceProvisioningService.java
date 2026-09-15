package com.knowledgelink.source.application;

import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceConnectionRepository;
import com.knowledgelink.source.domain.SourceKind;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.source.domain.SourceScopeRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 스크립트(seed)용 연결·scope 등록. 있는 것은 건너뛰고 없는 것만 추가한다. */
@Service
@RequiredArgsConstructor
public class SourceProvisioningService {

    public record ScopeSpec(String externalId, String displayKey) {
    }

    public record Result(UUID connectionId, boolean connectionCreated, int scopesCreated) {
    }

    private final SourceConnectionRepository connectionRepository;
    private final SourceScopeRepository scopeRepository;

    @Transactional
    public Result provisionIfAbsent(UUID workspaceId, String name, SourceKind kind, String siteId, String baseUrl,
                                    String credentialRef, List<ScopeSpec> scopes) {
        SourceConnection connection = connectionRepository.findByWorkspaceIdAndName(workspaceId, name).orElse(null);
        boolean connectionCreated = false;
        if (connection == null) {
            connection = connectionRepository.save(
                    SourceConnection.create(workspaceId, name, kind, siteId, baseUrl, credentialRef));
            connectionCreated = true;
        } else if (connection.getKind() != kind) {
            throw new IllegalArgumentException("이미 다른 종류로 등록된 연결 이름입니다: " + name);
        }

        int scopesCreated = 0;
        for (ScopeSpec spec : scopes) {
            if (scopeRepository.findByConnectionIdAndExternalId(connection.getId(), spec.externalId()).isEmpty()) {
                scopeRepository.save(SourceScope.create(connection, spec.externalId(), spec.displayKey()));
                scopesCreated++;
            }
        }
        return new Result(connection.getId(), connectionCreated, scopesCreated);
    }
}
