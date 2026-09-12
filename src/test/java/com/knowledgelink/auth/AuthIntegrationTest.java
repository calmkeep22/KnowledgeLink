package com.knowledgelink.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgelink.account.domain.AccountRole;
import com.knowledgelink.common.web.RequestIdFilter;
import com.knowledgelink.support.ApiSession;
import com.knowledgelink.support.IntegrationTest;
import com.knowledgelink.support.TestFixtures;
import com.knowledgelink.workspace.domain.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** T01: 로그인·초기 비밀번호 변경·로그아웃·비활성 계정·CSRF. */
@IntegrationTest
class AuthIntegrationTest {

    private static final String INITIAL_PASSWORD = "initial-password-1";
    private static final String READY_PASSWORD = "ready-password-123";
    private static final String NEW_PASSWORD = "brand-new-password-9";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        Workspace workspace = fixtures.workspace("Test Org");
        fixtures.newAccount(workspace, "member.new", AccountRole.MEMBER, INITIAL_PASSWORD);
        fixtures.readyAccount(workspace, "member.ready", AccountRole.MEMBER, READY_PASSWORD);
        fixtures.readyAccount(workspace, "admin.ready", AccountRole.ADMIN, READY_PASSWORD);
        fixtures.readyAccount(workspace, "member.off", AccountRole.MEMBER, READY_PASSWORD);
        fixtures.deactivate("member.off");
    }

    private ApiSession newSession() {
        return new ApiSession(mockMvc);
    }

    @Nested
    class 로그인 {

        @Test
        void CSRF_토큰이_없으면_거절한다() throws Exception {
            newSession().postWithoutCsrf("/api/v1/auth/login",
                            "{\"loginId\":\"member.ready\",\"password\":\"%s\"}".formatted(READY_PASSWORD))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }

        @Test
        void 없는_계정_틀린_비밀번호_비활성_계정은_같은_응답이다() throws Exception {
            String[][] attempts = {
                    {"nobody.here", READY_PASSWORD},
                    {"member.ready", "wrong-password-000"},
                    {"member.off", READY_PASSWORD}
            };
            for (String[] attempt : attempts) {
                newSession().login(attempt[0], attempt[1])
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                        .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
            }
        }

        @Test
        void 로그인하면_세션_ID가_바뀐다() throws Exception {
            ApiSession session = newSession().fetchCsrf();
            String anonymousSession = session.sessionCookieValue();

            session.login("member.ready", READY_PASSWORD).andExpect(status().isOk());

            assertThat(session.sessionCookieValue()).isNotNull().isNotEqualTo(anonymousSession);
        }

        @Test
        void 허용되지_않은_필드는_UNKNOWN_FIELD다() throws Exception {
            newSession().fetchCsrf()
                    .postJson("/api/v1/auth/login",
                            "{\"loginId\":\"member.ready\",\"password\":\"x\",\"role\":\"ADMIN\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
                    .andExpect(jsonPath("$.details[0].field").value("role"));
        }
    }

    @Nested
    class 초기_비밀번호_변경 {

        @Test
        void 변경_전에는_인증_API_외에는_막힌다() throws Exception {
            ApiSession session = newSession();
            session.login("member.new", INITIAL_PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mustChangePassword").value(true));

            session.get("/api/v1/auth/me").andExpect(status().isOk());
            session.get("/api/v1/scopes")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        }

        @Test
        void 변경하면_API가_열리고_같은_계정의_다른_세션은_끊긴다() throws Exception {
            ApiSession current = newSession();
            ApiSession other = newSession();
            current.login("member.new", INITIAL_PASSWORD).andExpect(status().isOk());
            other.login("member.new", INITIAL_PASSWORD).andExpect(status().isOk());
            String beforeChange = current.sessionCookieValue();

            current.postJson("/api/v1/auth/password",
                            "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(INITIAL_PASSWORD, NEW_PASSWORD))
                    .andExpect(status().isNoContent());

            assertThat(current.sessionCookieValue()).isNotEqualTo(beforeChange);
            current.get("/api/v1/auth/me").andExpect(jsonPath("$.mustChangePassword").value(false));
            // 보안 필터를 통과해 라우팅 단계까지 온다(아직 없는 API라 404).
            current.get("/api/v1/scopes").andExpect(status().isNotFound());
            other.get("/api/v1/auth/me")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void 정책_위반과_현재_비밀번호_불일치를_필드별로_알려준다() throws Exception {
            ApiSession session = newSession();
            session.login("member.new", INITIAL_PASSWORD).andExpect(status().isOk());

            session.postJson("/api/v1/auth/password",
                            "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(INITIAL_PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.details[0].field").value("newPassword"));

            session.postJson("/api/v1/auth/password",
                            "{\"currentPassword\":\"wrong-password-0\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("currentPassword"));
        }
    }

    @Nested
    class 세션_유지와_종료 {

        @Test
        void 로그아웃하면_세션이_끝난다() throws Exception {
            ApiSession session = newSession();
            session.login("member.ready", READY_PASSWORD).andExpect(status().isOk());

            session.postJson("/api/v1/auth/logout", "{}").andExpect(status().isNoContent());

            session.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
        }

        @Test
        void 로그인_중_비활성화되면_다음_요청부터_끊긴다() throws Exception {
            ApiSession session = newSession();
            session.login("member.ready", READY_PASSWORD).andExpect(status().isOk());
            session.get("/api/v1/auth/me").andExpect(status().isOk());

            fixtures.deactivate("member.ready");

            session.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
        }

        @Test
        void 관리자_API는_ADMIN만_통과한다() throws Exception {
            ApiSession member = newSession();
            member.login("member.ready", READY_PASSWORD).andExpect(status().isOk());
            member.get("/api/v1/admin/connections")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            ApiSession admin = newSession();
            admin.login("admin.ready", READY_PASSWORD).andExpect(status().isOk());
            admin.get("/api/v1/admin/connections").andExpect(status().isNotFound());
        }

        @Test
        void 인증_응답은_캐시되지_않고_오류에는_요청_ID가_담긴다() throws Exception {
            ApiSession session = newSession();
            session.login("member.ready", READY_PASSWORD).andExpect(status().isOk());
            session.get("/api/v1/auth/me")
                    .andExpect(header().string("Cache-Control", containsString("no-store")));

            MvcResult unauthorized = newSession().get("/api/v1/auth/me")
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            String headerId = unauthorized.getResponse().getHeader(RequestIdFilter.HEADER);
            assertThat(headerId).isNotBlank();
            assertThat(unauthorized.getResponse().getContentAsString()).contains(headerId);
        }
    }
}
