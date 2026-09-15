package com.knowledgelink.ops.seed;

import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.source.domain.SourceKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 운영 스크립트 입력. 비밀번호·토큰 값은 받지 않고, 값을 담은 환경 변수 이름만 받는다.
 * 예시는 config/seed.example.yml.
 */
@Validated
@ConfigurationProperties("kl.seed")
public record SeedProperties(
        @NotBlank String workspaceName,
        @Valid List<AccountSeed> accounts,
        @Valid List<ConnectionSeed> connections,
        @Valid List<GrantSeed> grants) {

    public SeedProperties {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        connections = connections == null ? List.of() : List.copyOf(connections);
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    public record AccountSeed(
            @NotBlank String loginId,
            @NotNull AccountRole role,
            @NotBlank String initialPasswordEnv) {
    }

    /** credentialRef는 토큰을 담은 환경 변수 이름이다. */
    public record ConnectionSeed(
            @NotBlank String name,
            @NotNull SourceKind kind,
            String siteId,
            @NotBlank String baseUrl,
            @NotBlank String credentialRef,
            @Valid List<ScopeSeed> scopes) {

        public ConnectionSeed {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    public record ScopeSeed(@NotBlank String externalId, @NotBlank String displayKey) {
    }

    /** scopes 항목은 "연결이름:표시키" 형식이다(예: demo-jira:PAY, demo-github:owner/repo). */
    public record GrantSeed(@NotBlank String loginId, List<@NotBlank String> scopes) {

        public GrantSeed {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
