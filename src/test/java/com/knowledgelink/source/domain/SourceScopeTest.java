package com.knowledgelink.source.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceScopeTest {

    private final SourceConnection jira = SourceConnection.create(UUID.randomUUID(), "demo-jira",
            SourceKind.JIRA_CLOUD, "site-1", "https://demo.atlassian.net", "KL_JIRA_TOKEN");
    private final SourceConnection github = SourceConnection.create(UUID.randomUUID(), "demo-github",
            SourceKind.GITHUB, null, "https://api.github.com", "KL_GITHUB_TOKEN");

    @Test
    void 연결의_조직과_ID를_물려받고_켜진_상태로_만들어진다() {
        SourceScope scope = SourceScope.create(jira, " 10000 ", "PAY");

        assertThat(scope.getWorkspaceId()).isEqualTo(jira.getWorkspaceId());
        assertThat(scope.getConnectionId()).isEqualTo(jira.getId());
        assertThat(scope.getExternalId()).isEqualTo("10000");
        assertThat(scope.isEnabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "PAY-1", "P", "1PAY", "PAY KEY"})
    void Jira_표시_키는_대문자로_시작하는_프로젝트_키여야_한다(String key) {
        assertThatThrownBy(() -> SourceScope.create(jira, "10000", key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GitHub_표시_키는_owner_repo_형식이어야_한다() {
        assertThat(SourceScope.create(github, "555", "calmkeep22/payment-service").getDisplayKey())
                .isEqualTo("calmkeep22/payment-service");
        assertThatThrownBy(() -> SourceScope.create(github, "555", "payment-service"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceScope.create(github, "555", "-owner/repo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 외부_ID는_비어_있으면_안_된다() {
        assertThatThrownBy(() -> SourceScope.create(jira, "  ", "PAY")).isInstanceOf(IllegalArgumentException.class);
    }
}
