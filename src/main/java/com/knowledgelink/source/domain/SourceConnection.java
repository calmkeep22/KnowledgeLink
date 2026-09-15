package com.knowledgelink.source.domain;

import com.knowledgelink.common.id.UuidV7;
import com.knowledgelink.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Jira 사이트 또는 GitHub 연결 1개. 인증 토큰은 저장하지 않고, 토큰을 담은 환경 변수 이름만 저장한다.
 */
@Entity
@Table(name = "source_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceConnection extends BaseEntity {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");
    private static final Pattern CREDENTIAL_REF = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** 조직 안에서 연결을 가리키는 이름(운영 스크립트·관리 화면용). */
    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private SourceKind kind;

    /** Jira Cloud 사이트 식별자. 같은 사이트를 두 번 연결하지 않는 기준이다. GitHub는 null. */
    @Column(name = "site_id", updatable = false)
    private String siteId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** 토큰이 들어 있는 환경 변수 이름. */
    @Column(name = "credential_ref", nullable = false)
    private String credentialRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConnectionStatus status;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private SourceConnection(UUID workspaceId, String name, SourceKind kind, String siteId,
                             String baseUrl, String credentialRef) {
        super(UuidV7.next());
        this.workspaceId = workspaceId;
        this.name = name;
        this.kind = kind;
        this.siteId = siteId;
        this.baseUrl = baseUrl;
        this.credentialRef = credentialRef;
        this.status = ConnectionStatus.ACTIVE;
    }

    public static SourceConnection create(UUID workspaceId, String name, SourceKind kind, String siteId,
                                          String baseUrl, String credentialRef) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(kind, "kind");
        require(name != null && NAME.matcher(name).matches(),
                "연결 이름은 소문자·숫자·하이픈 2~50자여야 합니다: " + name);
        String normalizedSiteId = siteId == null || siteId.isBlank() ? null : siteId.strip();
        if (kind == SourceKind.JIRA_CLOUD) {
            require(normalizedSiteId != null, "Jira Cloud 연결에는 site_id가 필요합니다.");
        } else {
            require(normalizedSiteId == null, "GitHub 연결에는 site_id를 쓰지 않습니다.");
        }
        // 입력값을 메시지에 넣지 않는다. 토큰을 잘못 붙여 넣었을 수 있다.
        require(credentialRef != null && CREDENTIAL_REF.matcher(credentialRef).matches(),
                "credentialRef에는 토큰이 아니라 환경 변수 이름(대문자·숫자·밑줄)을 적어야 합니다.");
        return new SourceConnection(workspaceId, name, kind, normalizedSiteId, normalizeBaseUrl(baseUrl), credentialRef);
    }

    /** https만 허용한다(로컬 테스트 서버는 http 허용). URL에 인증 정보·쿼리를 넣지 못하게 한다. */
    static String normalizeBaseUrl(String raw) {
        URI uri;
        try {
            uri = new URI(raw == null ? "" : raw.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("base URL 형식이 올바르지 않습니다.");
        }
        String host = uri.getHost();
        boolean localhost = "localhost".equals(host) || "127.0.0.1".equals(host);
        boolean allowedScheme = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && localhost);
        require(host != null && allowedScheme, "base URL은 https여야 합니다(로컬 테스트 서버만 http 허용).");
        require(uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null,
                "base URL에 인증 정보·쿼리·fragment를 넣을 수 없습니다.");
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
