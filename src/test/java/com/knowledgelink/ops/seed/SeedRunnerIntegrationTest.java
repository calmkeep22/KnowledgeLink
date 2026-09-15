package com.knowledgelink.ops.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.access.application.GrantProvisioningService;
import com.knowledgelink.account.application.AccountProvisioningService;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.ops.seed.SeedProperties.AccountSeed;
import com.knowledgelink.ops.seed.SeedProperties.ConnectionSeed;
import com.knowledgelink.ops.seed.SeedProperties.GrantSeed;
import com.knowledgelink.ops.seed.SeedProperties.ScopeSeed;
import com.knowledgelink.source.application.SourceProvisioningService;
import com.knowledgelink.source.domain.SourceKind;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.WorkspaceRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

@IntegrationTest
class SeedRunnerIntegrationTest {

    private static final List<AccountSeed> ACCOUNTS = List.of(
            new AccountSeed("admin", AccountRole.ADMIN, "SEED_ADMIN_PW"),
            new AccountSeed("dev.kim", AccountRole.MEMBER, "SEED_MEMBER_PW"));

    private static final List<ConnectionSeed> CONNECTIONS = List.of(
            new ConnectionSeed("demo-jira", SourceKind.JIRA_CLOUD, "site-1", "https://demo.atlassian.net",
                    "KL_JIRA_TOKEN", List.of(new ScopeSeed("10000", "PAY"), new ScopeSeed("10001", "ORD"))),
            new ConnectionSeed("demo-github", SourceKind.GITHUB, null, "https://api.github.com",
                    "KL_GITHUB_TOKEN", List.of(new ScopeSeed("555", "calmkeep22/payment-service"))));

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    AccountProvisioningService accountProvisioning;

    @Autowired
    SourceProvisioningService sourceProvisioning;

    @Autowired
    GrantProvisioningService grantProvisioning;

    @Autowired
    TestFixtures fixtures;

    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("SEED_ADMIN_PW", "seed-owner-pass-123")
            .withProperty("SEED_MEMBER_PW", "member-password-123");

    @BeforeEach
    void setUp() {
        fixtures.reset();
    }

    private void seed(List<GrantSeed> grants) {
        new SeedRunner(new SeedProperties("Seed Org", ACCOUNTS, CONNECTIONS, grants), workspaceRepository,
                accountProvisioning, sourceProvisioning, grantProvisioning, environment)
                .run(new DefaultApplicationArguments());
    }

    @Test
    void 두_번_실행해도_조직_계정_연결_scope_grant가_한_번만_생긴다() {
        List<GrantSeed> grants = List.of(
                new GrantSeed("dev.kim", List.of("demo-jira:PAY", "demo-github:calmkeep22/payment-service")));

        seed(grants);
        seed(grants);

        assertThat(fixtures.count("workspace")).isEqualTo(1);
        assertThat(fixtures.count("account")).isEqualTo(2);
        assertThat(fixtures.count("source_connection")).isEqualTo(2);
        assertThat(fixtures.count("source_scope")).isEqualTo(3);
        assertThat(fixtures.count("scope_grant")).isEqualTo(2);
    }

    @Test
    void ADMIN에게_grant하면_설정_오류로_거절한다() {
        assertThatThrownBy(() -> seed(List.of(new GrantSeed("admin", List.of("demo-jira:PAY")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void 형식이_틀리거나_없는_scope_참조는_거절한다() {
        assertThatThrownBy(() -> seed(List.of(new GrantSeed("dev.kim", List.of("demo-jira-PAY")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'연결이름:표시키' 형식");
        assertThatThrownBy(() -> seed(List.of(new GrantSeed("dev.kim", List.of("demo-jira:NOPE")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("특정할 수 없습니다");
    }
}
