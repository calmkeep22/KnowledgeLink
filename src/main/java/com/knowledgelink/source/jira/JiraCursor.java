package com.knowledgelink.source.jira;

import java.time.Duration;
import java.time.Instant;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.Objects;

/** Jira 증분 동기화의 high-water mark. 같은 수정 시각은 불변 issue ID로 순서를 정한다. */
public record JiraCursor(Instant updatedAt, String externalId) {

    public static final Duration OVERLAP = Duration.ofMinutes(10);
    private static final Comparator<JiraCursor> ORDER = Comparator.comparing(JiraCursor::updatedAt)
            .thenComparing(JiraCursor::externalId, JiraCursor::compareExternalId);

    public JiraCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId가 필요합니다.");
        }
    }

    public Instant queryFrom() {
        return updatedAt.minus(OVERLAP);
    }

    public JiraCursor max(JiraCursor other) {
        return ORDER.compare(this, other) >= 0 ? this : other;
    }

    private static int compareExternalId(String left, String right) {
        if (left.chars().allMatch(Character::isDigit) && right.chars().allMatch(Character::isDigit)) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        return left.compareTo(right);
    }
}
