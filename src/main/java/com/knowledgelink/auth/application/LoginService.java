package com.knowledgelink.auth.application;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.LoginIds;
import com.knowledgelink.account.domain.PasswordPolicy;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final String timingPadHash;

    public LoginService(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.timingPadHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 계정 없음·비밀번호 불일치·비활성 계정을 같은 오류로 응답한다.
     * 계정이 없어도 같은 비용의 해시 비교를 수행해 응답 시간으로 계정 존재 여부가 드러나지 않게 한다.
     */
    @Transactional(readOnly = true)
    public AccountPrincipal authenticate(String rawLoginId, String rawPassword) {
        Optional<Account> account = LoginIds.normalize(rawLoginId).flatMap(accountRepository::findByLoginId);
        String hash = account.map(Account::getPasswordHash).orElse(timingPadHash);
        boolean withinLimit = PasswordPolicy.fitsEncoderLimit(rawPassword);
        boolean matches = passwordEncoder.matches(withinLimit ? rawPassword : "", hash) && withinLimit;

        if (account.isEmpty() || !matches || !account.get().isActive()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return AccountPrincipal.from(account.get());
    }
}
