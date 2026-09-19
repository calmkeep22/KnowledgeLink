package com.knowledgelink.source.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JiraCursorTest {

    @Test
    void 증분_조회는_저장된_수정_시각보다_10분_앞에서_시작한다() {
        JiraCursor cursor = new JiraCursor(Instant.parse("2026-09-18T01:00:00Z"), "10002");

        assertThat(cursor.queryFrom()).isEqualTo(Instant.parse("2026-09-18T00:50:00Z"));
    }

    @Test
    void 같은_수정_시각이면_불변_ID가_큰_cursor를_고른다() {
        Instant updatedAt = Instant.parse("2026-09-18T01:00:00Z");

        assertThat(new JiraCursor(updatedAt, "10002").max(new JiraCursor(updatedAt, "10003")).externalId())
                .isEqualTo("10003");
    }
}
