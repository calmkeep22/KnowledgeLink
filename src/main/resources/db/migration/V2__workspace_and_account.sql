CREATE TABLE workspace (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT workspace_name_uk UNIQUE (name),
    CONSTRAINT workspace_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100)
);

-- MVP는 단일 조직이므로 login_id를 전역 unique로 둔다(명세의 조직 내 unique보다 강한 제약).
CREATE TABLE account (
    id                   uuid        PRIMARY KEY,
    workspace_id         uuid        NOT NULL REFERENCES workspace (id),
    login_id             text        NOT NULL,
    password_hash        text        NOT NULL,
    role                 text        NOT NULL,
    active               boolean     NOT NULL DEFAULT true,
    must_change_password boolean     NOT NULL DEFAULT true,
    credential_version   integer     NOT NULL DEFAULT 0,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    CONSTRAINT account_login_id_uk UNIQUE (login_id),
    CONSTRAINT account_login_id_ck CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{2,49}$'),
    CONSTRAINT account_role_ck CHECK (role IN ('MEMBER', 'ADMIN'))
);

CREATE INDEX account_workspace_idx ON account (workspace_id);
