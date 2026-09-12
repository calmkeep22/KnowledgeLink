package com.knowledgelink.account.domain;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;

    /**
     * BCrypt는 입력의 앞 72바이트만 사용하고, Spring Security는 이를 넘는 입력을 예외로 거절한다.
     * 한도를 넘는 비밀번호는 설정 단계에서 막고 로그인 비교에서도 먼저 걸러 낸다.
     */
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean fitsEncoderLimit(String raw) {
        return raw != null && raw.getBytes(UTF_8).length <= MAX_UTF8_BYTES;
    }

    /** 새 비밀번호의 정책 위반 사유를 모두 반환한다. 비어 있으면 통과다. */
    public static List<String> violations(String loginId, String candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate == null || candidate.isBlank()) {
            reasons.add("비밀번호를 입력해 주세요.");
            return reasons;
        }
        if (candidate.codePointCount(0, candidate.length()) < MIN_LENGTH) {
            reasons.add(MIN_LENGTH + "자 이상이어야 합니다.");
        }
        if (!fitsEncoderLimit(candidate)) {
            reasons.add(MAX_UTF8_BYTES + "바이트(영문 72자, 한글 약 24자)를 넘을 수 없습니다.");
        }
        if (!candidate.equals(candidate.strip())) {
            reasons.add("앞뒤 공백을 사용할 수 없습니다.");
        }
        if (loginId != null && candidate.toLowerCase(Locale.ROOT).contains(loginId)) {
            reasons.add("아이디를 포함할 수 없습니다.");
        }
        return reasons;
    }
}
