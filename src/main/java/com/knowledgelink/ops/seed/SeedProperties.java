package com.knowledgelink.ops.seed;

import com.knowledgelink.account.domain.AccountRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 운영 스크립트 입력. 비밀번호 대신 비밀번호를 담은 환경 변수 이름만 받는다.
 * 예시는 config/seed.example.yml.
 */
@Validated
@ConfigurationProperties("kl.seed")
public record SeedProperties(
        @NotBlank String workspaceName,
        @Valid List<AccountSeed> accounts) {

    public SeedProperties {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public record AccountSeed(
            @NotBlank String loginId,
            @NotNull AccountRole role,
            @NotBlank String initialPasswordEnv) {
    }
}
