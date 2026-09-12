package com.knowledgelink.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.account.domain.AccountRepository;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AccountProvisioningIntegrationTest {

    @Autowired
    AccountProvisioningService provisioningService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TestFixtures fixtures;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        workspace = fixtures.workspace("Seed Org");
    }

    @Test
    void 두_번_실행해도_계정은_하나이고_기존_비밀번호를_덮어쓰지_않는다() {
        var first = provisioningService.provisionIfAbsent(workspace.getId(), "Dev.Kim", AccountRole.MEMBER, "first-password-1");
        String firstHash = accountRepository.findByLoginId("dev.kim").orElseThrow().getPasswordHash();

        var second = provisioningService.provisionIfAbsent(workspace.getId(), "dev.kim", AccountRole.ADMIN, "second-password-2");

        assertThat(first).isEqualTo(AccountProvisioningService.Result.CREATED);
        assertThat(second).isEqualTo(AccountProvisioningService.Result.ALREADY_EXISTS);
        var saved = accountRepository.findByLoginId("dev.kim").orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo(firstHash);
        assertThat(saved.getRole()).isEqualTo(AccountRole.MEMBER);
        assertThat(saved.isMustChangePassword()).isTrue();
    }

    @Test
    void 정책에_맞지_않는_초기_비밀번호는_거절한다() {
        assertThatThrownBy(() -> provisioningService.provisionIfAbsent(
                workspace.getId(), "dev.lee", AccountRole.MEMBER, "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("short");
        assertThat(accountRepository.existsByLoginId("dev.lee")).isFalse();
    }
}
