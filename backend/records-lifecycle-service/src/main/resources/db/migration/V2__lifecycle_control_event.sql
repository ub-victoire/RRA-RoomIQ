CREATE TABLE lifecycle_control_event (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(24) NOT NULL,
    aggregate_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    actor_subject VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_sha256 CHAR(64),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_lifecycle_control_event_aggregate_type
        CHECK (aggregate_type IN ('POLICY', 'HOLD', 'DISPOSAL')),
    CONSTRAINT ck_lifecycle_control_event_sequence
        CHECK (sequence_no > 0),
    CONSTRAINT uq_lifecycle_control_event_sequence
        UNIQUE (aggregate_type, aggregate_id, sequence_no)
);

CREATE INDEX ix_lifecycle_control_event_aggregate_recorded
    ON lifecycle_control_event (aggregate_type, aggregate_id, recorded_at);

CREATE OR REPLACE FUNCTION lifecycle_policy_reject_untracked_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'policy lifecycle writes require the approved event and authorization path'
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION lifecycle_control_event_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'lifecycle control evidence is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER lifecycle_control_event_no_update_or_delete
BEFORE UPDATE OR DELETE ON lifecycle_control_event
FOR EACH ROW
EXECUTE FUNCTION lifecycle_control_event_reject_mutation();

CREATE TRIGGER lifecycle_control_event_no_truncate
BEFORE TRUNCATE ON lifecycle_control_event
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_control_event_reject_mutation();

CREATE TRIGGER lifecycle_policy_version_no_truncate
BEFORE TRUNCATE ON lifecycle_policy_version
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_policy_reject_untracked_mutation();

CREATE TRIGGER lifecycle_policy_rule_no_truncate
BEFORE TRUNCATE ON lifecycle_policy_rule
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_policy_reject_untracked_mutation();
