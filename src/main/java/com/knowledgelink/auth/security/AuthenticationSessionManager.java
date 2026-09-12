package com.knowledgelink.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** JSON 로그인은 Security 필터 밖(컨트롤러)에서 인증하므로 세션 회전·컨텍스트 저장을 여기서 명시적으로 수행한다. */
@Component
public class AuthenticationSessionManager {

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthenticationSessionManager(SessionAuthenticationStrategy sessionAuthenticationStrategy,
                                        SecurityContextRepository securityContextRepository) {
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    /** 세션 ID를 바꾸고(세션 고정 방지) CSRF 토큰을 폐기한 뒤 인증 정보를 세션에 저장한다. */
    public void signIn(AccountPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = AccountAuthentications.of(principal);
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        store(authentication, request, response);
    }

    /** 비밀번호 변경 뒤 현재 세션만 새 자격 버전으로 이어 가고 세션 ID를 바꾼다. */
    public void refresh(AccountPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        request.changeSessionId();
        store(AccountAuthentications.of(principal), request, response);
    }

    private void store(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
