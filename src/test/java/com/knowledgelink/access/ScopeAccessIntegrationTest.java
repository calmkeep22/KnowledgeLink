package com.knowledgelink.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgelink.access.application.ScopeAccessPolicy;
import com.knowledgelink.account.domain.Account;
import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.auth.security.AccountPrincipal;
import com.knowledgelink.common.error.ApiException;
import com.knowledgelink.common.error.ErrorCode;
import com.knowledgelink.source.domain.SourceConnection;
import com.knowledgelink.source.domain.SourceScope;
import com.knowledgelink.support.ApiSession;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 2단계 접근 모델: 조직·grant·scope 켜짐 여부에 따라 볼 수 있는 scope가 정해지고, 권한 밖은 404다. */
@IntegrationTest
class ScopeAccessIntegrationTest {

    private static final String PASSWORD = "ready-password-123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    ScopeAccessPolicy accessPolicy;

    private Account adminA;
    private Account memberA;
    private SourceScope pay;
    private SourceScope ord;
    private SourceScope otherOrgScope;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        Workspace orgA = fixtures.workspace("Org A");
        Workspace orgB = fixtures.workspace("Org B");
        adminA = fixtures.readyAccount(orgA, "admin.a", AccountRole.ADMIN, PASSWORD);
        memberA = fixtures.readyAccount(orgA, "member.a", AccountRole.MEMBER, PASSWORD);
        fixtures.readyAccount(orgA, "member.none", AccountRole.MEMBER, PASSWORD);

        SourceConnection jira = fixtures.jiraConnection(orgA, "a-jira", "site-a");
        SourceConnection github = fixtures.githubConnection(orgA, "a-github");
        pay = fixtures.scope(jira, "10000", "PAY");
        ord = fixtures.scope(jira, "10001", "ORD");
        SourceScope old = fixtures.scope(jira, "10002", "OLD");
        SourceScope repo = fixtures.scope(github, "555", "acme/payment");
        fixtures.disable(old);

        otherOrgScope = fixtures.scope(fixtures.jiraConnection(orgB, "b-jira", "site-b"), "20000", "BPAY");

        fixtures.grant(pay, memberA);
        fixtures.grant(repo, memberA);
        fixtures.grant(old, memberA);
    }

    private ApiSession loggedIn(String loginId) throws Exception {
        ApiSession session = new ApiSession(mockMvc);
        session.login(loginId, PASSWORD).andExpect(status().isOk());
        return session;
    }

    @Test
    void ADMIN은_같은_조직의_켜진_scope를_모두_본다() throws Exception {
        loggedIn("admin.a").get("/api/v1/scopes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].displayKey", contains("acme/payment", "ORD", "PAY")))
                .andExpect(jsonPath("$.items[*].kind", contains("GITHUB_REPOSITORY", "JIRA_PROJECT", "JIRA_PROJECT")));
    }

    @Test
    void MEMBER는_grant가_있는_켜진_scope만_본다() throws Exception {
        loggedIn("member.a").get("/api/v1/scopes")
                .andExpect(jsonPath("$.items[*].displayKey", contains("acme/payment", "PAY")));
    }

    @Test
    void grant가_없으면_빈_목록이다() throws Exception {
        loggedIn("member.none").get("/api/v1/scopes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void 로그인_중_grant를_회수하면_다음_요청부터_사라진다() throws Exception {
        ApiSession session = loggedIn("member.a");
        session.get("/api/v1/scopes").andExpect(jsonPath("$.items[*].displayKey", contains("acme/payment", "PAY")));

        fixtures.revoke(pay, memberA);

        session.get("/api/v1/scopes").andExpect(jsonPath("$.items[*].displayKey", contains("acme/payment")));
    }

    @Test
    void scope를_끄면_ADMIN에게도_보이지_않는다() throws Exception {
        ApiSession admin = loggedIn("admin.a");

        fixtures.disable(ord);

        admin.get("/api/v1/scopes").andExpect(jsonPath("$.items[*].displayKey", contains("acme/payment", "PAY")));
    }

    @Test
    void 응답에_연결의_인증_정보나_URL이_없다() throws Exception {
        String body = loggedIn("admin.a").get("/api/v1/scopes").andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("credential", "KL_JIRA_TOKEN", "atlassian.net", "baseUrl");
    }

    @Test
    void 다른_조직의_scope는_ADMIN이어도_없는_자료와_같은_404다() {
        AccountPrincipal admin = AccountPrincipal.from(adminA);

        assertNotFound(() -> accessPolicy.requireAccessible(admin, otherOrgScope.getId()));
        assertNotFound(() -> accessPolicy.narrow(admin, List.of(pay.getId(), otherOrgScope.getId())));
    }

    @Test
    void grant가_없는_같은_조직_scope도_MEMBER에게는_404다() {
        AccountPrincipal member = AccountPrincipal.from(memberA);

        accessPolicy.requireAccessible(member, pay.getId());
        assertNotFound(() -> accessPolicy.requireAccessible(member, ord.getId()));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
