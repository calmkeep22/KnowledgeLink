package com.knowledgelink.demo.domain;

import java.time.Instant;
import java.util.Objects;

/** AI에 제공할 수 있도록 가명 처리된 공개 데모 활동. id는 근거 인용의 기준이다. */
public record DemoActivity(
        String id,
        String projectId,
        String memberId,
        String memberName,
        ActivityKind kind,
        String title,
        String status,
        Instant occurredAt,
        String sourceUrl,
        String details
) {
    public DemoActivity {
        requireText(id, "id");
        requireText(projectId, "projectId");
        requireText(memberId, "memberId");
        requireText(memberName, "memberName");
        Objects.requireNonNull(kind, "kind");
        requireText(title, "title");
        requireText(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(sourceUrl, "sourceUrl");
        details = details == null ? "" : details.strip();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다.");
        }
    }
}
