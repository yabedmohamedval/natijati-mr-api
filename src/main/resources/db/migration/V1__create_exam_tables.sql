CREATE TABLE exam_session (
    id BIGSERIAL PRIMARY KEY,
    exam_type VARCHAR(20) NOT NULL,
    exam_year INTEGER NOT NULL,
    session_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_filename VARCHAR(255),
    result_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_exam_session UNIQUE (exam_type, exam_year, session_type)
);

CREATE TABLE bac_result (
    id BIGSERIAL PRIMARY KEY,
    exam_session_id BIGINT NOT NULL REFERENCES exam_session(id) ON DELETE CASCADE,
    candidate_number BIGINT NOT NULL,
    name_fr VARCHAR(255),
    name_ar VARCHAR(255),
    wilaya_fr VARCHAR(150),
    wilaya_ar VARCHAR(150),
    exam_center_fr VARCHAR(255),
    exam_center_ar VARCHAR(255),
    institution_fr VARCHAR(255),
    institution_ar VARCHAR(255),
    series_code VARCHAR(30),
    series_fr VARCHAR(150),
    series_ar VARCHAR(150),
    score NUMERIC(18, 12),
    decision_code VARCHAR(40),
    decision_original VARCHAR(150),
    CONSTRAINT uk_bac_result UNIQUE (exam_session_id, candidate_number)
);

CREATE TABLE bepc_result (
    id BIGSERIAL PRIMARY KEY,
    exam_session_id BIGINT NOT NULL REFERENCES exam_session(id) ON DELETE CASCADE,
    candidate_number BIGINT NOT NULL,
    name_fr VARCHAR(255) NOT NULL,
    wilaya_code INTEGER,
    wilaya_fr VARCHAR(150),
    exam_center_fr VARCHAR(255),
    score NUMERIC(18, 12),
    decision_code VARCHAR(40),
    decision_original VARCHAR(150),
    CONSTRAINT uk_bepc_result UNIQUE (exam_session_id, candidate_number)
);

CREATE TABLE c1as_result (
    id BIGSERIAL PRIMARY KEY,
    exam_session_id BIGINT NOT NULL REFERENCES exam_session(id) ON DELETE CASCADE,
    candidate_number BIGINT,
    dossier_number BIGINT NOT NULL,
    name_ar VARCHAR(255) NOT NULL,
    candidate_type VARCHAR(30),
    school_ar VARCHAR(255),
    wilaya_ar VARCHAR(150),
    moughataa_ar VARCHAR(150),
    exam_center_ar VARCHAR(255),
    total_score NUMERIC(18, 12),
    CONSTRAINT uk_c1as_result_dossier UNIQUE (exam_session_id, dossier_number)
);

CREATE INDEX idx_bac_search
    ON bac_result (exam_session_id, candidate_number);

CREATE INDEX idx_bepc_search
    ON bepc_result (exam_session_id, candidate_number);

CREATE INDEX idx_c1as_dossier_search
    ON c1as_result (exam_session_id, dossier_number);

CREATE INDEX idx_c1as_candidate_number
    ON c1as_result (exam_session_id, candidate_number);
