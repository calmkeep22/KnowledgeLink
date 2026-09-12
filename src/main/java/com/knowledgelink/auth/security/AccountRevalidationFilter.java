package com.knowledgelink.auth.security;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 세션에 저장된 인증 정보를 요청마다 DB 계정과 대조한다.
 *
 * <ul>
 *   <li>계정이 없거나 비활성화됐거나 다른 곳에서 비밀번호가 바뀌었으면 세션을 끊고 익명으로 처리한다.</li>
 *   <li>역할·초기 비밀번호 변경 여부만 바뀌었으면 세션의 인증 정보를 갱신한다.</li>
 * </ul>
 *
 * <p>PK 조회 한 번으로 운영 스크립트의 계정 변경이 다음 요청부터 바로 반영된다.
 */
public class AccountRevalidationFilter extends OncePerRequestFilter {

    private final AccountRepository accountRepository;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AccountRevalidationFilter(AccountRepository accountRepository,
                                     SecurityContextRepository securityContextRepository) {
        this.accountRepository = accountRepository;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal current) {
            revalidate(current, request, response);
        }
        chain.doFilter(request, response);
    }

    private void revalidate(AccountPrincipal current, HttpServletRequest request, HttpServletResponse response) {
        Optional<Account> account = accountRepository.findById(current.accountId());
        if (account.isEmpty()
                || !account.get().isActive()
                || account.get().getCredentialVersion() != current.credentialVersion()) {
            contextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            return;
        }
        AccountPrincipal fresh = AccountPrincipal.from(account.get());
        if (!fresh.equals(current)) {
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(AccountAuthentications.of(fresh));
            contextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
        }
    }
}
