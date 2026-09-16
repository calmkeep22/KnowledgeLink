-- 작업 큐와 서버 전체 실행 슬롯(명세 03 5장). 선점·lease 갱신·완료는 애플리케이션이 조건부 SQL로 원자적으로 바꾸고,
-- DB는 상태와 소유 정보가 어긋난 행을 거절한다.
CREATE TABLE job (
    id               uuid        PRIMARY KEY,
    workspace_id     uuid        NOT NULL REFERENCES workspace (id),
    kind             text        NOT NULL,
    status           text        NOT NULL,
    scope_id         uuid,
    target_id        uuid,
    stage            text,
    attempt_count    integer     NOT NULL DEFAULT 0,
    owner_id         text,
    run_token        uuid,
    lease_expires_at timestamptz,
    next_run_at      timestamptz NOT NULL,
    error_code       text,
    snapshot         jsonb       NOT NULL DEFAULT '{}',
    month_key        text        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT job_id_run_token_uk UNIQUE (id, run_token),
    CONSTRAINT job_scope_fk FOREIGN KEY (scope_id, workspace_id) REFERENCES source_scope (id, workspace_id),
    CONSTRAINT job_kind_ck CHECK (kind IN ('SYNC', 'RECONCILE', 'INDEX', 'ANALYSIS', 'QUESTION', 'CARD', 'LINK_SUGGEST')),
    CONSTRAINT job_status_ck CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    -- 실행 중일 때만, 그리고 실행 중이면 반드시 소유자·run_token·lease가 있다.
    CONSTRAINT job_running_owner_ck CHECK ((status = 'RUNNING') = (owner_id IS NOT NULL)
        AND (status = 'RUNNING') = (run_token IS NOT NULL)
        AND (status = 'RUNNING') = (lease_expires_at IS NOT NULL)),
    CONSTRAINT job_owner_id_ck CHECK (owner_id IS NULL OR length(owner_id) BETWEEN 1 AND 200),
    CONSTRAINT job_attempt_ck CHECK (attempt_count >= 0),
    CONSTRAINT job_scope_required_ck CHECK (kind NOT IN ('SYNC', 'RECONCILE') OR scope_id IS NOT NULL),
    -- 예산·건수는 작업이 처음 들어온 달(Asia/Seoul)에 귀속한다. 수동 재시도도 바꾸지 않는다.
    CONSTRAINT job_month_key_ck CHECK (month_key ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT job_snapshot_ck CHECK (jsonb_typeof(snapshot) = 'object')
);

CREATE INDEX job_queued_idx ON job (next_run_at, id) WHERE status = 'QUEUED';
CREATE INDEX job_retry_wait_idx ON job (next_run_at) WHERE status = 'RETRY_WAIT';
CREATE INDEX job_scope_idx ON job (scope_id) WHERE scope_id IS NOT NULL;

-- scope당 활성 SYNC는 하나다(명세 03 3장).
CREATE UNIQUE INDEX job_active_sync_uk ON job (scope_id)
    WHERE kind = 'SYNC' AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');

-- 슬롯은 workspace·scope·kind와 무관하게 서버 전체에 하나씩이다. 슬롯당 실행 작업은 하나다.
CREATE TABLE execution_slot (
    slot_key         text        PRIMARY KEY,
    job_id           uuid        UNIQUE,
    run_token        uuid,
    lease_expires_at timestamptz,
    CONSTRAINT execution_slot_key_ck CHECK (slot_key IN ('SYNC', 'INDEX', 'AI')),
    CONSTRAINT execution_slot_owner_ck CHECK ((job_id IS NULL) = (run_token IS NULL)
        AND (job_id IS NULL) = (lease_expires_at IS NULL)),
    -- 슬롯을 가진 작업의 현재 run_token과 같아야 한다. 작업과 슬롯은 한 transaction에서 차례로 바뀌므로 commit 때 검사한다.
    CONSTRAINT execution_slot_job_fk FOREIGN KEY (job_id, run_token) REFERENCES job (id, run_token)
        DEFERRABLE INITIALLY DEFERRED
);

INSERT INTO execution_slot (slot_key) VALUES ('SYNC'), ('INDEX'), ('AI');
