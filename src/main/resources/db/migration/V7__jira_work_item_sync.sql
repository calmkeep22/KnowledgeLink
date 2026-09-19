ALTER TABLE source_scope
    ADD COLUMN sync_cursor jsonb,
    ADD COLUMN last_reconciled_at timestamptz;

CREATE TABLE work_item (
    id                   uuid        PRIMARY KEY,
    workspace_id         uuid        NOT NULL,
    source_connection_id uuid        NOT NULL,
    scope_id             uuid        NOT NULL,
    external_id          text        NOT NULL,
    issue_key            text        NOT NULL,
    title                text        NOT NULL,
    body                 text,
    body_masked          text,
    type                 text        NOT NULL,
    status               text        NOT NULL,
    resolution           text,
    labels               jsonb       NOT NULL DEFAULT '[]'::jsonb,
    components           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    comments             jsonb       NOT NULL DEFAULT '[]'::jsonb,
    resolved_at          timestamptz,
    source_updated_at    timestamptz NOT NULL,
    content_hash         text        NOT NULL,
    visibility           text        NOT NULL DEFAULT 'ACTIVE',
    url                  text        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT work_item_connection_external_uk UNIQUE (source_connection_id, external_id),
    CONSTRAINT work_item_scope_fk FOREIGN KEY (scope_id, workspace_id)
        REFERENCES source_scope (id, workspace_id),
    CONSTRAINT work_item_connection_fk FOREIGN KEY (source_connection_id, workspace_id)
        REFERENCES source_connection (id, workspace_id),
    CONSTRAINT work_item_visibility_ck CHECK (visibility IN ('ACTIVE', 'HIDDEN')),
    CONSTRAINT work_item_external_id_ck CHECK (length(external_id) BETWEEN 1 AND 200),
    CONSTRAINT work_item_issue_key_ck CHECK (length(issue_key) BETWEEN 1 AND 200)
);

CREATE INDEX work_item_scope_idx ON work_item (scope_id);
CREATE INDEX work_item_updated_idx ON work_item (source_connection_id, source_updated_at, external_id);
