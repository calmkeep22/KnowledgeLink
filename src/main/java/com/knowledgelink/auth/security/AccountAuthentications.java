package com.knowledgelink.auth.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class AccountAuthentications {

    /** 초기 비밀번호를 변경한 계정에만 부여한다. 인증·비밀번호 변경 외 API는 이 권한을 요구한다. */
    public static final String CREDENTIALS_READY = "CREDENTIALS_READY";

    private AccountAuthentications() {
    }

    public static UsernamePasswordAuthenticationToken of(AccountPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>(2);
        authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
        if (!principal.mustChangePassword()) {
            authorities.add(new SimpleGrantedAuthority(CREDENTIALS_READY));
        }
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }
}
