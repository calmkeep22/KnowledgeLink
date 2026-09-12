package com.knowledgelink.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void 정책을_지키면_위반_사유가_없다() {
        assertThat(PasswordPolicy.violations("dev.kim", "correct-horse-battery")).isEmpty();
    }

    @Test
    void 짧은_비밀번호는_거절한다() {
        assertThat(PasswordPolicy.violations("dev.kim", "short-pw")).containsExactly("12자 이상이어야 합니다.");
    }

    @Test
    void 한글_25자는_72바이트를_넘어_거절한다() {
        String korean = "가".repeat(25);

        assertThat(PasswordPolicy.fitsEncoderLimit(korean)).isFalse();
        assertThat(PasswordPolicy.violations("dev.kim", korean)).hasSize(1);
    }

    @Test
    void 영문_72자까지는_인코더_한도_안이다() {
        assertThat(PasswordPolicy.fitsEncoderLimit("a".repeat(72))).isTrue();
        assertThat(PasswordPolicy.fitsEncoderLimit("a".repeat(73))).isFalse();
    }

    @Test
    void 아이디를_포함하거나_앞뒤_공백이_있으면_거절한다() {
        assertThat(PasswordPolicy.violations("dev.kim", "DEV.KIM-password")).containsExactly("아이디를 포함할 수 없습니다.");
        assertThat(PasswordPolicy.violations("dev.kim", " padded-password ")).containsExactly("앞뒤 공백을 사용할 수 없습니다.");
    }

    @Test
    void 비어_있으면_입력_요청만_반환한다() {
        assertThat(PasswordPolicy.violations("dev.kim", "   ")).containsExactly("비밀번호를 입력해 주세요.");
        assertThat(PasswordPolicy.violations("dev.kim", null)).containsExactly("비밀번호를 입력해 주세요.");
    }
}
