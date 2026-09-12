package com.knowledgelink.auth.api.dto;

import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.auth.security.AccountPrincipal;
import java.util.UUID;

public record MeResponse(UUID accountId, String loginId, AccountRole role, boolean mustChangePassword) {

    public static MeResponse from(AccountPrincipal principal) {
        return new MeResponse(principal.accountId(), principal.loginId(), principal.role(),
                principal.mustChangePassword());
    }
}
