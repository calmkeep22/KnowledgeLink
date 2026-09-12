package com.knowledgelink.auth.application;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.PasswordPolicy;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 비밀번호를 바꾸고 자격 버전을 올린다. 같은 계정의 다른 세션은 다음 요청에서 자동으로 끊긴다.
     *
     * @return 현재 세션에 다시 저장할 인증 주체
     */
    @Transactional
    public AccountPrincipal change(UUID accountId, String currentPassword, String newPassword) {
        Account account = accountRepository.findById(accountId)
                .filter(Account::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));

        if (!PasswordPolicy.fitsEncoderLimit(currentPassword)
                || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw ApiException.invalidField("currentPassword", "현재 비밀번호가 일치하지 않습니다.");
        }
        List<String> violations = PasswordPolicy.violations(account.getLoginId(), newPassword);
        if (!violations.isEmpty()) {
            throw ApiException.invalidField("newPassword", violations);
        }
        if (newPassword.equals(currentPassword)) {
            throw ApiException.invalidField("newPassword", "현재 비밀번호와 다른 비밀번호를 사용해 주세요.");
        }

        account.changePassword(passwordEncoder.encode(newPassword));
        return AccountPrincipal.from(account);
    }
}
