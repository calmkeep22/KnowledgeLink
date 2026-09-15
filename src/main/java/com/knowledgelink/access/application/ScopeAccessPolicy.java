package com.knowledgelink.access.application;

import com.knowledgelink.access.domain.ScopeGrantRepository;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.source.domain.SourceScopeRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * scope 접근 판단. ADMIN은 같은 조직의 켜진 scope 전부, MEMBER는 grant가 있는 켜진 scope만 본다.
 *
 * <p>권한 밖 자료는 403이 아니라 없는 자료와 같은 404로 응답한다. 403은 자료가 있다는 사실을 드러낸다.
 * 역할은 요청마다 DB로 다시 확인된 인증 주체({@code AccountRevalidationFilter})에서 읽는다.
 */
@Service
@RequiredArgsConstructor
public class ScopeAccessPolicy {

    private static final String CACHE_ATTRIBUTE = ScopeAccessPolicy.class.getName() + ".accessible.";

    private final SourceScopeRepository scopeRepository;
    private final ScopeGrantRepository grantRepository;

    /**
     * 웹 요청 안에서는 한 번만 계산해 요청 속성에 둔다. 요청이 끝나면 버리므로 권한 회수가 다음 요청에 바로 반영된다.
     * 요청 밖(백그라운드 작업)에서는 매번 계산한다.
     */
    public AccessibleScopes accessibleScopes(AccountPrincipal principal) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return load(principal);
        }
        String key = CACHE_ATTRIBUTE + principal.accountId();
        if (request.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof AccessibleScopes cached) {
            return cached;
        }
        AccessibleScopes loaded = load(principal);
        request.setAttribute(key, loaded, RequestAttributes.SCOPE_REQUEST);
        return loaded;
    }

    /** 볼 수 없는 scope면 없는 자료와 같은 404. */
    public void requireAccessible(AccountPrincipal principal, UUID scopeId) {
        if (!accessibleScopes(principal).contains(scopeId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /**
     * 요청이 고른 scope로 범위를 좁힌다. 고르지 않았으면 볼 수 있는 전체다.
     * 볼 수 없는 scope가 섞이면 존재 여부를 드러내지 않도록 404, 볼 수 있는 scope가 하나도 없으면 422.
     */
    public AccessibleScopes narrow(AccountPrincipal principal, Collection<UUID> requested) {
        AccessibleScopes accessible = accessibleScopes(principal);
        if (requested == null || requested.isEmpty()) {
            if (accessible.isEmpty()) {
                throw new ApiException(ErrorCode.NO_ACCESSIBLE_SCOPE);
            }
            return accessible;
        }
        for (UUID scopeId : requested) {
            if (!accessible.contains(scopeId)) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        return AccessibleScopes.of(requested);
    }

    private AccessibleScopes load(AccountPrincipal principal) {
        List<UUID> ids = principal.isAdmin()
                ? scopeRepository.findEnabledIdsByWorkspaceId(principal.workspaceId())
                : grantRepository.findEnabledScopeIdsGrantedTo(principal.accountId(), principal.workspaceId());
        return AccessibleScopes.of(ids);
    }
}
