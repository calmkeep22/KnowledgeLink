package com.knowledgelink.account.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 로그인 ID는 소문자로 정규화해 저장·비교한다. DB CHECK 제약과 같은 패턴을 쓴다. */
public final class LoginIds {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,49}$");

    private LoginIds() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return PATTERN.matcher(normalized).matches() ? Optional.of(normalized) : Optional.empty();
    }
}
