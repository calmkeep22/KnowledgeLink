package com.knowledgelink.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgelink.access.domain.ScopeGrant;
import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 애플리케이션 검증을 우회해도 DB가 지키는 규칙. 서비스 코드에 실수가 있어도 조직 경계와 인증 정보 규칙이 깨지지 않는다.
 */
@IntegrationTest
class SourceSchemaConstraintsIntegrationTest {

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Workspace orgA;
    private Workspace orgB;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        orgA = fixtures.workspace("Org A");
        orgB = fixtures.workspace("Org B");
    }

    @Test
    void 같은_Jira_사이트를_한_조직에_두_번_연결할_수_없다() {
        fixtures.jiraConnection(orgA, "jira-one", "site-1");

        assertThatThrownBy(() -> fixtures.jiraConnection(orgA, "jira-two", "site-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixtures.jiraConnection(orgB, "jira-one", "site-1").getId()).isNotNull();
    }

    @Test
    void 다른_조직의_연결에_scope를_붙일_수_없다() {
        SourceConnection connectionOfA = fixtures.jiraConnection(orgA, "a-jira", "site-a");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO source_scope (id, workspace_id, connection_id, external_id, display_key, created_at)"
                        + " VALUES (?, ?, ?, '1', 'PAY', ?)",
                UuidV7.next(), orgB.getId(), connectionOfA.getId(), Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 다른_조직의_계정에게_grant할_수_없다() {
        SourceScope scopeOfA = fixtures.scope(fixtures.jiraConnection(orgA, "a-jira", "site-a"), "1", "PAY");
        Account memberOfB = fixtures.readyAccount(orgB, "member.b", AccountRole.MEMBER, "ready-password-123");

        assertThatThrownBy(() -> ScopeGrant.of(scopeOfA, memberOfB)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO scope_grant (id, workspace_id, scope_id, account_id, created_at) VALUES (?, ?, ?, ?, ?)",
                UuidV7.next(), orgA.getId(), scopeOfA.getId(), memberOfB.getId(), Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_계정과_scope의_grant는_하나뿐이다() {
        SourceScope scope = fixtures.scope(fixtures.jiraConnection(orgA, "a-jira", "site-a"), "1", "PAY");
        Account member = fixtures.readyAccount(orgA, "member.a", AccountRole.MEMBER, "ready-password-123");
        fixtures.grant(scope, member);

        assertThatThrownBy(() -> fixtures.grant(scope, member)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 토큰처럼_보이는_credential_ref와_site_id를_가진_GitHub_연결은_DB도_거절한다() {
        String insert = "INSERT INTO source_connection (id, workspace_id, name, kind, site_id, base_url, credential_ref,"
                + " created_at) VALUES (?, ?, ?, 'GITHUB', ?, 'https://api.github.com', ?, ?)";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UuidV7.next(), orgA.getId(), "gh-token", null,
                "ghp_1234567890abcdef", Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UuidV7.next(), orgA.getId(), "gh-site", "site-x",
                "KL_GITHUB_TOKEN", Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
