package com.knowledgelink.access.application;

import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.source.domain.ScopeSummary;
import com.knowledgelink.source.domain.SourceScopeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScopeQueryService {

    private final ScopeAccessPolicy accessPolicy;
    private final SourceScopeRepository scopeRepository;

    /** 요청자가 볼 수 있는 scope만. scope 수는 운영 제한(프로젝트·저장소 각 2개 내외)으로 작아 페이지 없이 반환한다. */
    public List<ScopeSummary> visibleScopes(AccountPrincipal principal) {
        AccessibleScopes accessible = accessPolicy.accessibleScopes(principal);
        if (accessible.isEmpty()) {
            return List.of();
        }
        return scopeRepository.findSummaries(accessible.ids());
    }
}
