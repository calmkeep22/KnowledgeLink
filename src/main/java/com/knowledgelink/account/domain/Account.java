package com.knowledgelink.account.domain;

import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    /** workspace는 ID로만 참조한다. 계정 조회 때마다 조직 엔티티를 함께 읽을 이유가 없다. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "login_id", nullable = false, updatable = false)
    private String loginId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private AccountRole role;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** 비밀번호가 바뀔 때마다 증가한다. 세션에 저장된 값과 다르면 그 세션은 더 이상 유효하지 않다. */
    @Column(name = "credential_version", nullable = false)
    private int credentialVersion;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private Account(UUID workspaceId, String loginId, String passwordHash, AccountRole role) {
        super(UuidV7.next());
        this.workspaceId = workspaceId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.mustChangePassword = true;
    }

    /** 운영 스크립트로 만든 계정은 첫 로그인 후 비밀번호를 바꿔야 한다. */
    public static Account provision(UUID workspaceId, String normalizedLoginId, String passwordHash, AccountRole role) {
        return new Account(workspaceId, normalizedLoginId, passwordHash, role);
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
        this.credentialVersion++;
    }

    public void deactivate() {
        this.active = false;
    }
}
