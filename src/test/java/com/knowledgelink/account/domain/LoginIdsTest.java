package com.knowledgelink.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LoginIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin", "  Admin  ", "ADMIN"})
    void 앞뒤_공백을_지우고_소문자로_정규화한다(String raw) {
        assertThat(LoginIds.normalize(raw)).contains("admin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev.kim", "dev_kim-01", "a1b"})
    void 허용된_문자는_그대로_통과한다(String raw) {
        assertThat(LoginIds.normalize(raw)).contains(raw);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ab", "-abc", ".abc", "김민수", "has space", "a@b.com"})
    void 형식에_맞지_않으면_비어_있다(String raw) {
        assertThat(LoginIds.normalize(raw)).isEmpty();
    }
}
