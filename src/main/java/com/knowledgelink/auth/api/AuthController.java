package com.knowledgelink.auth.api;

import com.knowledgelink.auth.api.dto.CsrfResponse;
import com.knowledgelink.auth.api.dto.LoginRequest;
import com.knowledgelink.auth.api.dto.MeResponse;
import com.knowledgelink.auth.api.dto.PasswordChangeRequest;
import com.knowledgelink.auth.application.LoginService;
import com.knowledgelink.auth.application.PasswordChangeService;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.auth.security.AuthenticationSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그아웃은 Spring Security LogoutFilter가 처리한다(POST /api/v1/auth/logout → 204). */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginService loginService;
    private final PasswordChangeService passwordChangeService;
    private final AuthenticationSessionManager sessionManager;

    /** 로그인 전후 모두 호출한다. 로그인하면 기존 토큰이 폐기되므로 새로 받아야 한다. */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        AccountPrincipal principal = loginService.authenticate(body.loginId(), body.password());
        sessionManager.signIn(principal, request, response);
        return MeResponse.from(principal);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return MeResponse.from(principal);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AccountPrincipal principal,
                               @Valid @RequestBody PasswordChangeRequest body,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        AccountPrincipal updated = passwordChangeService.change(
                principal.accountId(), body.currentPassword(), body.newPassword());
        sessionManager.refresh(updated, request, response);
    }
}
