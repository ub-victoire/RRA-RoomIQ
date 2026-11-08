CREATE TABLE lifecycle_policy_version (
    policy_version_id UUID PRIMARY KEY,
    version_label VARCHAR(64) NOT NULL UNIQUE,
    document_sha256 CHAR(64) NOT NULL,
    approval_reference VARCHAR(128),
    policy_version VARCHAR(64),
    effective_from TIMESTAMP WITH TIME ZONE,
    effective_to TIMESTAMP WITH TIME ZONE,
    approving_authority_ref VARCHAR(128),
    approved_by_subject VARCHAR(128),
    approved_at TIMESTAMP WITH TIME ZONE,
    legal_validation_ref VARCHAR(128),
    records_validation_ref VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_lifecycle_policy_version_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'SUPERSEDED', 'WITHDRAWN')),
    CONSTRAINT ck_lifecycle_policy_version_effective_interval
        CHECK (effective_to IS NULL OR (effective_from IS NOT NULL AND effective_to > effective_from)),
    CONSTRAINT ck_lifecycle_policy_version_approval
        CHECK (status = 'DRAFT' OR (
            approval_reference IS NOT NULL
            AND policy_version IS NOT NULL
            AND effective_from IS NOT NULL
            AND approving_authority_ref IS NOT NULL
            AND approved_by_subject IS NOT NULL
            AND approved_at IS NOT NULL
            AND legal_validation_ref IS NOT NULL
            AND records_validation_ref IS NOT NULL
        ))
);

CREATE INDEX ix_lifecycle_policy_version_effective
    ON lifecycle_policy_version (status, effective_from, effective_to);

CREATE TABLE lifecycle_policy_rule (
    rule_id UUID PRIMARY KEY,
    policy_version_id UUID NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    rule_source VARCHAR(24) NOT NULL,
    duration_value SMALLINT,
    duration_unit VARCHAR(8),
    start_basis_code VARCHAR(40) NOT NULL,
    schedule_reference VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_lifecycle_policy_rule_version
        FOREIGN KEY (policy_version_id) REFERENCES lifecycle_policy_version (policy_version_id),
    CONSTRAINT ck_lifecycle_policy_rule_source
        CHECK (rule_source IN ('RRA_PROPOSED', 'RRA_APPROVED', 'STATUTORY')),
    CONSTRAINT ck_lifecycle_policy_rule_duration
        CHECK (
            (duration_value IS NOT NULL AND duration_value > 0
                AND duration_unit IN ('YEAR', 'DAY') AND schedule_reference IS NULL)
            OR (duration_value IS NULL AND duration_unit IS NULL AND schedule_reference IS NOT NULL)
        ),
    CONSTRAINT ck_lifecycle_policy_rule_statutory_reference
        CHECK (rule_source <> 'STATUTORY' OR schedule_reference IS NOT NULL),
    CONSTRAINT uq_lifecycle_policy_rule_code
        UNIQUE (policy_version_id, rule_code)
);

CREATE INDEX ix_lifecycle_policy_rule_category
    ON lifecycle_policy_rule (policy_version_id, category_code);

CREATE FUNCTION lifecycle_policy_reject_untracked_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'policy changes require an approved lifecycle control event schema'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER lifecycle_policy_version_controlled_mutation
BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_policy_version
FOR EACH ROW
EXECUTE FUNCTION lifecycle_policy_reject_untracked_mutation();

CREATE TRIGGER lifecycle_policy_rule_controlled_mutation
BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_policy_rule
FOR EACH ROW
EXECUTE FUNCTION lifecycle_policy_reject_untracked_mutation();
