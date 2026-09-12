package com.knowledgelink.auth.security;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRole;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 세션에 저장되는 인증 주체. 요청마다 DB의 계정과 비교해 다르면 갱신하거나 세션을 끊는다.
 * {@link #getName()}은 accountId이며 Spring Session의 principal 색인에 쓰인다.
 */
public record AccountPrincipal(
        UUID accountId,
        UUID workspaceId,
        String loginId,
        AccountRole role,
        boolean mustChangePassword,
        int credentialVersion) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static AccountPrincipal from(Account account) {
        return new AccountPrincipal(
                account.getId(),
                account.getWorkspaceId(),
                account.getLoginId(),
                account.getRole(),
                account.isMustChangePassword(),
                account.getCredentialVersion());
    }

    @Override
    public String getName() {
        return accountId.toString();
    }

    public boolean isAdmin() {
        return role == AccountRole.ADMIN;
    }
}
