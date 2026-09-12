package com.knowledgelink.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class LoginServiceTest {

    private static final String PASSWORD = "correct-horse-battery";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PasswordEncoder passwordEncoder = spy(PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private LoginService loginService;
    private Account account;

    @BeforeEach
    void setUp() {
        loginService = new LoginService(accountRepository, passwordEncoder);
        account = Account.provision(UUID.randomUUID(), "dev.kim", passwordEncoder.encode(PASSWORD), AccountRole.MEMBER);
        given(accountRepository.findByLoginId("dev.kim")).willReturn(Optional.of(account));
        given(accountRepository.findByLoginId("nobody")).willReturn(Optional.empty());
    }

    @Test
    void 아이디는_정규화해서_찾고_인증_주체를_돌려준다() {
        AccountPrincipal principal = loginService.authenticate("  DEV.KIM ", PASSWORD);

        assertThat(principal.accountId()).isEqualTo(account.getId());
        assertThat(principal.mustChangePassword()).isTrue();
    }

    @Test
    void 없는_계정도_해시_비교를_수행하고_같은_오류를_낸다() {
        assertInvalidCredentials("nobody", PASSWORD);

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void 비밀번호가_틀리면_같은_오류를_낸다() {
        assertInvalidCredentials("dev.kim", "wrong-password-123");
    }

    @Test
    void 비활성_계정은_비밀번호가_맞아도_같은_오류를_낸다() {
        account.deactivate();

        assertInvalidCredentials("dev.kim", PASSWORD);
    }

    @Test
    void 인코더_한도를_넘는_입력은_예외_없이_같은_오류를_낸다() {
        assertInvalidCredentials("dev.kim", "a".repeat(100));
    }

    private void assertInvalidCredentials(String loginId, String password) {
        assertThatThrownBy(() -> loginService.authenticate(loginId, password))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }
}
