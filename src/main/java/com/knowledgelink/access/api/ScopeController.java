package com.knowledgelink.access.api;

import com.knowledgelink.access.api.dto.ScopeListResponse;
import com.knowledgelink.access.application.ScopeQueryService;
import com.knowledgelink.auth.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scopes")
@RequiredArgsConstructor
public class ScopeController {

    private final ScopeQueryService scopeQueryService;

    /** 내가 볼 수 있는 scope. 볼 수 있는 것이 없으면 빈 목록이다. */
    @GetMapping
    public ScopeListResponse list(@AuthenticationPrincipal AccountPrincipal principal) {
        return ScopeListResponse.from(scopeQueryService.visibleScopes(principal));
    }
}
