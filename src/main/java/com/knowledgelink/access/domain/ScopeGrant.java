package com.knowledgelink.access.domain;

import com.knowledgelink.account.domain.Account;
import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.common.persistence.BaseEntity;
import com.knowledgelink.source.domain.SourceScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** MEMBER 계정에게 scope 하나를 볼 권한을 준다. ADMIN은 조직의 모든 scope를 보므로 grant가 필요 없다. */
@Entity
@Table(name = "scope_grant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScopeGrant extends BaseEntity {

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    private UUID scopeId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    private ScopeGrant(UUID workspaceId, UUID scopeId, UUID accountId) {
        super(UuidV7.next());
        this.workspaceId = workspaceId;
        this.scopeId = scopeId;
        this.accountId = accountId;
    }

    /** 같은 조직인지 먼저 확인한다. DB 복합 FK도 같은 규칙을 강제한다. */
    public static ScopeGrant of(SourceScope scope, Account account) {
        if (!scope.getWorkspaceId().equals(account.getWorkspaceId())) {
            throw new IllegalArgumentException("다른 조직의 계정과 scope는 연결할 수 없습니다.");
        }
        return new ScopeGrant(scope.getWorkspaceId(), scope.getId(), account.getId());
    }
}
