package com.knowledgelink.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 브라우저 하나를 흉내 내는 테스트 클라이언트. 세션 쿠키와 CSRF 토큰을 요청 사이에 유지한다.
 */
public class ApiSession {

    public static final String SESSION_COOKIE = "SESSION";

    private final MockMvc mockMvc;
    private Cookie sessionCookie;
    private String csrfToken;

    public ApiSession(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    public ApiSession fetchCsrf() throws Exception {
        String body = get("/api/v1/auth/csrf")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        csrfToken = JsonPath.read(body, "$.token");
        return this;
    }

    /** CSRF 토큰을 받고 로그인한 뒤, 로그인으로 폐기된 토큰을 다시 받는다. */
    public ResultActions login(String loginId, String password) throws Exception {
        fetchCsrf();
        ResultActions result = postJson("/api/v1/auth/login",
                "{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password));
        if (result.andReturn().getResponse().getStatus() == 200) {
            fetchCsrf();
        }
        return result;
    }

    public ResultActions get(String url) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url));
    }

    public ResultActions postJson(String url, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return perform(request);
    }

    public ResultActions postWithoutCsrf(String url, String json) throws Exception {
        return perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public String sessionCookieValue() {
        return sessionCookie == null ? null : sessionCookie.getValue();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        if (sessionCookie != null) {
            request.cookie(sessionCookie);
        }
        ResultActions result = mockMvc.perform(request);
        Cookie updated = result.andReturn().getResponse().getCookie(SESSION_COOKIE);
        if (updated != null) {
            sessionCookie = updated.getMaxAge() == 0 ? null : updated;
        }
        return result;
    }
}
