package com.knowledgelink.account.application;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.account.domain.LoginIds;
import com.knowledgelink.account.domain.PasswordPolicy;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 스크립트(seed)용 계정 등록. 화면 가입 경로와 같은 ID·비밀번호 검증을 적용한다. */
@Service
@RequiredArgsConstructor
public class AccountProvisioningService {

    public enum Result {
        CREATED,
        ALREADY_EXISTS
    }

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    /** 이미 있는 계정은 비밀번호·역할을 덮어쓰지 않는다. */
    @Transactional
    public Result provisionIfAbsent(UUID workspaceId, String rawLoginId, AccountRole role, String initialPassword) {
        String loginId = LoginIds.normalize(rawLoginId)
                .orElseThrow(() -> new IllegalArgumentException("로그인 ID 형식이 올바르지 않습니다: " + rawLoginId));
        if (accountRepository.existsByLoginId(loginId)) {
            return Result.ALREADY_EXISTS;
        }
        List<String> violations = PasswordPolicy.violations(loginId, initialPassword);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "초기 비밀번호가 정책에 맞지 않습니다(" + loginId + "): " + String.join(" ", violations));
        }
        accountRepository.save(Account.provision(workspaceId, loginId, passwordEncoder.encode(initialPassword), role));
        return Result.CREATED;
    }
}
