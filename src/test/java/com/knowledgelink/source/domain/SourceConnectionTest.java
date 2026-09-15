package com.knowledgelink.source.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceConnectionTest {

    private static final UUID WORKSPACE = UUID.randomUUID();

    private static SourceConnection github(String baseUrl, String credentialRef) {
        return SourceConnection.create(WORKSPACE, "demo-github", SourceKind.GITHUB, null, baseUrl, credentialRef);
    }

    @Test
    void 정상_연결은_ACTIVE로_만들어지고_URL_끝의_슬래시를_지운다() {
        SourceConnection connection = github("https://api.github.com/", "KL_GITHUB_TOKEN");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection.getBaseUrl()).isEqualTo("https://api.github.com");
    }

    @Test
    void Jira_연결에는_site_id가_필요하고_GitHub_연결에는_넣을_수_없다() {
        assertThatThrownBy(() -> SourceConnection.create(WORKSPACE, "demo-jira", SourceKind.JIRA_CLOUD, " ",
                "https://demo.atlassian.net", "KL_JIRA_TOKEN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceConnection.create(WORKSPACE, "demo-github", SourceKind.GITHUB, "site-1",
                "https://api.github.com", "KL_GITHUB_TOKEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghp_1234567890abcdefABCDEF", "kl_github_token", "KL", "KL-GITHUB-TOKEN"})
    void credentialRef에_토큰이나_잘못된_이름을_넣으면_거절하고_값을_메시지에_싣지_않는다(String credentialRef) {
        assertThatThrownBy(() -> github("https://api.github.com", credentialRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(credentialRef);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.github.com",
            "https://user:secret@api.github.com",
            "https://api.github.com?token=abc",
            "ftp://api.github.com",
            "not a url"
    })
    void 허용되지_않는_base_URL은_거절하고_값을_메시지에_싣지_않는다(String baseUrl) {
        assertThatThrownBy(() -> github(baseUrl, "KL_GITHUB_TOKEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("token=abc");
    }

    @Test
    void 로컬_테스트_서버는_http를_허용한다() {
        assertThat(github("http://localhost:8089", "KL_GITHUB_TOKEN").getBaseUrl()).isEqualTo("http://localhost:8089");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "Demo-Jira", "-demo", "demo_jira"})
    void 연결_이름은_소문자_숫자_하이픈만_허용한다(String name) {
        assertThatThrownBy(() -> SourceConnection.create(WORKSPACE, name, SourceKind.GITHUB, null,
                "https://api.github.com", "KL_GITHUB_TOKEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
