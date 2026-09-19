package com.knowledgelink.source.jira;

import com.knowledgelink.common.id.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Jira 동기화의 짧은 DB 작업. 외부 호출은 이 클래스의 transaction 밖에서 수행한다. */
@Repository
public class JiraSyncStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JiraSyncStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<JiraSyncTarget> findActiveTarget(UUID scopeId) {
        List<JiraSyncTarget> targets = jdbc.query("""
                SELECT s.workspace_id, s.connection_id, s.id, s.external_id, s.display_key,
                       c.base_url, c.credential_ref,
                       s.sync_cursor ->> 'updatedAt' AS cursor_updated_at,
                       s.sync_cursor ->> 'externalId' AS cursor_external_id
                  FROM source_scope s
                  JOIN source_connection c ON c.id = s.connection_id
                 WHERE s.id = ? AND s.enabled = true
                   AND c.kind = 'JIRA_CLOUD' AND c.status = 'ACTIVE'
                """, (rs, row) -> new JiraSyncTarget(
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getString("external_id"),
                rs.getString("display_key"),
                rs.getString("base_url"),
                rs.getString("credential_ref"),
                readCursor(rs.getString("cursor_updated_at"), rs.getString("cursor_external_id"))), scopeId);
        return targets.stream().findFirst();
    }

    /** 한 페이지와 high-water cursor를 함께 저장한다. 호출자는 JobContext.inLease 안에서 실행한다. */
    @Transactional
    public void savePage(JiraSyncTarget target, List<JiraIssue> issues, JiraCursor cursor) {
        assertStillActive(target);
        for (JiraIssue issue : issues) {
            if (!target.projectId().equals(issue.projectId())) {
                throw new IllegalArgumentException("다른 Jira 프로젝트의 이슈를 현재 scope에 저장할 수 없습니다.");
            }
            upsert(target, issue);
        }
        jdbc.update("UPDATE source_scope SET sync_cursor = ?::jsonb WHERE id = ?",
                writeCursor(cursor), target.scopeId());
    }

    /** 마지막 페이지 완료 시각은 job의 SUCCEEDED 전환과 같은 transaction에서 저장된다. */
    @Transactional
    public void complete(JiraSyncTarget target) {
        assertStillActive(target);
        jdbc.update("UPDATE source_scope SET last_synced_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), target.scopeId());
    }

    /** 인증·설정 오류를 연결에 남긴다. 비밀값이나 공급자 응답 본문은 저장하지 않는다. */
    @Transactional
    public void markConnectionError(UUID connectionId, String errorCode) {
        jdbc.update("UPDATE source_connection SET status = 'ERROR', last_error_code = ? WHERE id = ?",
                errorCode, connectionId);
    }

    private void assertStillActive(JiraSyncTarget target) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM source_scope s
                  JOIN source_connection c ON c.id = s.connection_id
                 WHERE s.id = ? AND s.connection_id = ? AND s.workspace_id = ?
                   AND s.enabled = true AND c.status = 'ACTIVE' AND c.kind = 'JIRA_CLOUD'
                """, Integer.class, target.scopeId(), target.connectionId(), target.workspaceId());
        if (count == null || count != 1) {
            throw new IllegalStateException("동기화 대상이 비활성화되었거나 연결 상태가 바뀌었습니다.");
        }
    }

    private void upsert(JiraSyncTarget target, JiraIssue issue) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO work_item (
                    id, workspace_id, source_connection_id, scope_id, external_id, issue_key,
                    title, body, body_masked, type, status, resolution, labels, components, comments,
                    resolved_at, source_updated_at, content_hash, visibility, url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, 'ACTIVE', ?, ?, ?)
                ON CONFLICT (source_connection_id, external_id) DO UPDATE SET
                    scope_id = EXCLUDED.scope_id,
                    issue_key = EXCLUDED.issue_key,
                    title = EXCLUDED.title,
                    body = EXCLUDED.body,
                    type = EXCLUDED.type,
                    status = EXCLUDED.status,
                    resolution = EXCLUDED.resolution,
                    labels = EXCLUDED.labels,
                    components = EXCLUDED.components,
                    comments = EXCLUDED.comments,
                    resolved_at = EXCLUDED.resolved_at,
                    source_updated_at = EXCLUDED.source_updated_at,
                    content_hash = EXCLUDED.content_hash,
                    visibility = 'ACTIVE',
                    url = EXCLUDED.url,
                    updated_at = EXCLUDED.updated_at
                WHERE work_item.source_updated_at <= EXCLUDED.source_updated_at
                """,
                UuidV7.next(), target.workspaceId(), target.connectionId(), target.scopeId(),
                issue.externalId(), issue.issueKey(), issue.title(), issue.body(), issue.type(), issue.status(),
                issue.resolution(), writeStrings(issue.labels()), writeStrings(issue.components()), writeComments(issue.comments()),
                timestamp(issue.resolvedAt()), Timestamp.from(issue.updatedAt()), contentHash(issue), issue.url(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private JiraCursor readCursor(String updatedAt, String externalId) {
        if (updatedAt == null || externalId == null) {
            return null;
        }
        try {
            return new JiraCursor(Instant.parse(updatedAt), externalId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("저장된 Jira sync cursor 형식이 올바르지 않습니다.", e);
        }
    }

    private String contentHash(JiraIssue issue) {
        try {
            byte[] canonical = issue.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Jira 이슈 content hash를 만들 수 없습니다.", e);
        }
    }

    private static String writeCursor(JiraCursor cursor) {
        return "{\"updatedAt\":" + json(cursor.updatedAt().toString())
                + ",\"externalId\":" + json(cursor.externalId()) + "}";
    }

    private static String writeStrings(List<String> values) {
        return values.stream().map(JiraSyncStore::json)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String writeComments(List<JiraComment> comments) {
        return comments.stream().map(comment -> "{"
                        + "\"externalId\":" + json(comment.externalId()) + ","
                        + "\"authorAccountId\":" + json(comment.authorAccountId()) + ","
                        + "\"body\":" + json(comment.body()) + ","
                        + "\"createdAt\":" + json(comment.createdAt() == null ? null : comment.createdAt().toString()) + ","
                        + "\"updatedAt\":" + json(comment.updatedAt() == null ? null : comment.updatedAt().toString())
                        + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
