-- 조직 경계를 DB가 보장하도록 (id, workspace_id) 복합 FK를 쓴다.
-- scope는 같은 조직의 연결에만, grant는 같은 조직의 계정·scope 사이에만 만들 수 있다.
ALTER TABLE account ADD CONSTRAINT account_id_workspace_uk UNIQUE (id, workspace_id);

CREATE TABLE source_connection (
    id              uuid        PRIMARY KEY,
    workspace_id    uuid        NOT NULL REFERENCES workspace (id),
    name            text        NOT NULL,
    kind            text        NOT NULL,
    site_id         text,
    base_url        text        NOT NULL,
    credential_ref  text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    last_error_code text,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    CONSTRAINT source_connection_id_workspace_uk UNIQUE (id, workspace_id),
    CONSTRAINT source_connection_name_uk UNIQUE (workspace_id, name),
    CONSTRAINT source_connection_name_ck CHECK (name ~ '^[a-z0-9][a-z0-9-]{1,49}$'),
    CONSTRAINT source_connection_kind_ck CHECK (kind IN ('JIRA_CLOUD', 'GITHUB')),
    CONSTRAINT source_connection_site_ck CHECK ((kind = 'JIRA_CLOUD') = (site_id IS NOT NULL)),
    CONSTRAINT source_connection_status_ck CHECK (status IN ('ACTIVE', 'ERROR', 'DISABLED')),
    -- 토큰 값이 아니라 환경 변수 이름만 저장한다. 토큰을 잘못 넣으면 형식 오류로 거절된다.
    CONSTRAINT source_connection_credential_ref_ck CHECK (credential_ref ~ '^[A-Z][A-Z0-9_]{2,63}$')
);

-- 같은 Jira 사이트를 한 조직에 두 번 연결하지 않는다(명세 T10).
CREATE UNIQUE INDEX source_connection_jira_site_uk
    ON source_connection (workspace_id, site_id) WHERE kind = 'JIRA_CLOUD';

CREATE TABLE source_scope (
    id             uuid        PRIMARY KEY,
    workspace_id   uuid        NOT NULL,
    connection_id  uuid        NOT NULL,
    external_id    text        NOT NULL,
    display_key    text        NOT NULL,
    enabled        boolean     NOT NULL DEFAULT true,
    last_synced_at timestamptz,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    CONSTRAINT source_scope_id_workspace_uk UNIQUE (id, workspace_id),
    CONSTRAINT source_scope_external_uk UNIQUE (connection_id, external_id),
    CONSTRAINT source_scope_connection_fk FOREIGN KEY (connection_id, workspace_id)
        REFERENCES source_connection (id, workspace_id),
    CONSTRAINT source_scope_external_id_ck CHECK (length(external_id) BETWEEN 1 AND 100),
    CONSTRAINT source_scope_display_key_ck CHECK (length(display_key) BETWEEN 1 AND 200)
);

CREATE INDEX source_scope_workspace_idx ON source_scope (workspace_id);

CREATE TABLE scope_grant (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL,
    scope_id     uuid        NOT NULL,
    account_id   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    -- (account_id, scope_id) 순서: "이 계정이 볼 수 있는 scope" 조회가 이 인덱스를 그대로 쓴다.
    CONSTRAINT scope_grant_account_scope_uk UNIQUE (account_id, scope_id),
    CONSTRAINT scope_grant_scope_fk FOREIGN KEY (scope_id, workspace_id)
        REFERENCES source_scope (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT scope_grant_account_fk FOREIGN KEY (account_id, workspace_id)
        REFERENCES account (id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX scope_grant_scope_idx ON scope_grant (scope_id);
