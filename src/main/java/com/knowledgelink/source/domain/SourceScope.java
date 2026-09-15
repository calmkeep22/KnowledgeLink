package com.knowledgelink.source.domain;

import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 접근 단위: Jira 프로젝트 1개 또는 GitHub 저장소 1개. 꺼진 scope는 수집·검색에서 빠진다. */
@Entity
@Table(name = "source_scope")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceScope extends BaseEntity {

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "connection_id", nullable = false, updatable = false)
    private UUID connectionId;

    /** 원본의 불변 ID(Jira project id, GitHub repository id). */
    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    /** 화면·이슈 키 매칭에 쓰는 이름(PAY, owner/repo). 원본에서 바뀔 수 있다. */
    @Column(name = "display_key", nullable = false)
    private String displayKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private SourceScope(UUID workspaceId, UUID connectionId, String externalId, String displayKey) {
        super(UuidV7.next());
        this.workspaceId = workspaceId;
        this.connectionId = connectionId;
        this.externalId = externalId;
        this.displayKey = displayKey;
        this.enabled = true;
    }

    public static SourceScope create(SourceConnection connection, String externalId, String displayKey) {
        String normalizedExternalId = externalId == null ? "" : externalId.strip();
        if (normalizedExternalId.isEmpty() || normalizedExternalId.length() > 100) {
            throw new IllegalArgumentException("외부 ID는 1~100자여야 합니다.");
        }
        ScopeKind kind = connection.getKind().scopeKind();
        String normalizedKey = displayKey == null ? "" : displayKey.strip();
        if (!kind.isValidDisplayKey(normalizedKey)) {
            throw new IllegalArgumentException(kind + " 표시 키 형식이 올바르지 않습니다: " + normalizedKey);
        }
        return new SourceScope(connection.getWorkspaceId(), connection.getId(), normalizedExternalId, normalizedKey);
    }

    public void disable() {
        this.enabled = false;
    }

    public void enable() {
        this.enabled = true;
    }
}
