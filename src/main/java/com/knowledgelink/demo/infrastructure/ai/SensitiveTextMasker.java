package com.knowledgelink.demo.infrastructure.ai;

import java.util.regex.Pattern;

/** 공개 데모 텍스트에 우연히 섞인 대표 비밀값·개인 식별값을 외부 전송 전에 가린다. */
final class SensitiveTextMasker {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|password|secret)\\s*[:=]\\s*[^\\s,;]+", Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?82[- ]?)?0?1[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)");

    String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String masked = EMAIL.matcher(value).replaceAll("[MASKED_EMAIL]");
        masked = BEARER.matcher(masked).replaceAll("Bearer [MASKED_SECRET]");
        masked = SECRET_ASSIGNMENT.matcher(masked).replaceAll("$1=[MASKED_SECRET]");
        return PHONE.matcher(masked).replaceAll("[MASKED_PHONE]");
    }
}
