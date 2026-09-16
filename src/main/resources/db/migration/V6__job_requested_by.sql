-- 작업을 요청한 계정. 동기화·색인처럼 시스템이 만든 작업은 NULL이며 ADMIN만 조회한다(명세 04 2장).
ALTER TABLE job ADD COLUMN requested_by uuid;

-- 같은 조직의 계정만 요청자가 될 수 있다.
ALTER TABLE job ADD CONSTRAINT job_requested_by_fk
    FOREIGN KEY (requested_by, workspace_id) REFERENCES account (id, workspace_id);

CREATE INDEX job_requested_by_idx ON job (requested_by) WHERE requested_by IS NOT NULL;
