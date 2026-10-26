CREATE TABLE import_execution (
    id uuid PRIMARY KEY,
    exam_type varchar(10) NOT NULL CHECK (exam_type IN ('BAC','BEPC','C1AS')),
    exam_year integer NOT NULL CHECK (exam_year BETWEEN 1900 AND 2100),
    session_type varchar(20) NOT NULL CHECK (session_type IN ('NORMAL','COMPLEMENTARY')),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','STARTING','VALIDATING','IMPORTING','READY','FAILED')),
    object_key varchar(100) NOT NULL UNIQUE,
    expected_rows bigint CHECK (expected_rows > 0),
    read_rows bigint NOT NULL DEFAULT 0 CHECK (read_rows >= 0),
    written_rows bigint NOT NULL DEFAULT 0 CHECK (written_rows >= 0),
    rejected_rows bigint NOT NULL DEFAULT 0 CHECK (rejected_rows >= 0),
    ignored_columns integer NOT NULL DEFAULT 0,
    session_id bigint REFERENCES exam_session(id),
    batch_job_execution_id bigint,
    cloud_run_execution_name varchar(512),
    dispatch_operation_name varchar(512),
    attempt_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    duration_ms bigint,
    error_code varchar(64),
    error_summary varchar(256),
    source_deleted_at timestamptz
);
CREATE UNIQUE INDEX import_execution_active_session ON import_execution(exam_type,exam_year,session_type)
    WHERE status IN ('QUEUED','STARTING','VALIDATING','IMPORTING');
CREATE INDEX import_execution_recent ON import_execution(created_at DESC);
