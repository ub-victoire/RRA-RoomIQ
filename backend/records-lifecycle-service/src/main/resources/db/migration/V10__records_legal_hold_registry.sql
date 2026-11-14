ALTER TABLE lifecycle_control_event
    DROP CONSTRAINT ck_lifecycle_control_event_type;

ALTER TABLE lifecycle_control_event
    ADD CONSTRAINT ck_lifecycle_control_event_type
    CHECK (event_type IN (
        'POLICY_CREATED',
        'POLICY_RULE_CREATED',
        'POLICY_RULE_MODIFIED',
        'POLICY_VALIDATED',
        'POLICY_ACTIVATED',
        'POLICY_SUPERSEDED',
        'POLICY_WITHDRAWN',
        'HOLD_CREATED',
        'HOLD_RELEASE_REQUESTED',
        'HOLD_RELEASE_VERIFIED'
    ));

CREATE TABLE lifecycle_legal_hold (
    hold_id UUID PRIMARY KEY,
    source_service VARCHAR(64) NOT NULL,
    record_type VARCHAR(64) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    instruction_reference VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    release_requested_by_user_id UUID,
    release_requested_at TIMESTAMP WITH TIME ZONE,
    release_reference VARCHAR(128),
    verified_by_user_id UUID,
    verified_at TIMESTAMP WITH TIME ZONE,
    verification_reference VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lifecycle_legal_hold_booking_scope
        CHECK (source_service = 'booking-service' AND record_type = 'BOOKING_REQUEST'),
    CONSTRAINT ck_lifecycle_legal_hold_record_id
        CHECK (record_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ck_lifecycle_legal_hold_instruction_reference
        CHECK (instruction_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_lifecycle_legal_hold_release_reference
        CHECK (release_reference IS NULL OR release_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_lifecycle_legal_hold_verification_reference
        CHECK (verification_reference IS NULL OR verification_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    CONSTRAINT ck_lifecycle_legal_hold_status
        CHECK (status IN ('PENDING_BLOCK', 'CONFLICTED', 'RELEASE_PENDING', 'RELEASED')),
    CONSTRAINT ck_lifecycle_legal_hold_version
        CHECK (version >= 0),
    CONSTRAINT ck_lifecycle_legal_hold_release_state
        CHECK (
            (status IN ('PENDING_BLOCK', 'CONFLICTED')
                AND release_requested_by_user_id IS NULL AND release_requested_at IS NULL
                AND release_reference IS NULL AND verified_by_user_id IS NULL
                AND verified_at IS NULL AND verification_reference IS NULL)
            OR (status = 'RELEASE_PENDING'
                AND release_requested_by_user_id IS NOT NULL AND release_requested_at IS NOT NULL
                AND release_reference IS NOT NULL AND verified_by_user_id IS NULL
                AND verified_at IS NULL AND verification_reference IS NULL)
            OR (status = 'RELEASED'
                AND release_requested_by_user_id IS NOT NULL AND release_requested_at IS NOT NULL
                AND release_reference IS NOT NULL AND verified_by_user_id IS NOT NULL
                AND verified_at IS NOT NULL AND verification_reference IS NOT NULL)
        ),
    CONSTRAINT ck_lifecycle_legal_hold_independent_verification
        CHECK (verified_by_user_id IS NULL OR verified_by_user_id <> release_requested_by_user_id)
);

CREATE INDEX ix_lifecycle_legal_hold_record_status
    ON lifecycle_legal_hold (source_service, record_type, record_id, status);

CREATE FUNCTION lifecycle_legal_hold_reject_untracked_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    event_id_text TEXT;
    event_type_value VARCHAR(48);
BEGIN
    event_id_text := current_setting('roomiq.lifecycle.hold_event_id', TRUE);
    IF event_id_text IS NULL OR event_id_text = '' THEN
        RAISE EXCEPTION 'legal-hold state changes require an authorized lifecycle event'
            USING ERRCODE = '55000';
    END IF;

    SELECT event_type INTO event_type_value
    FROM lifecycle_control_event
    WHERE event_id = event_id_text::UUID
      AND aggregate_type = 'HOLD'
      AND aggregate_id = COALESCE(NEW.hold_id, OLD.hold_id);

    IF event_type_value IS NULL THEN
        RAISE EXCEPTION 'legal-hold lifecycle event does not match the hold'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF event_type_value <> 'HOLD_CREATED' OR NEW.status NOT IN ('PENDING_BLOCK', 'CONFLICTED')
                OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'invalid legal-hold creation event or initial state'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'legal-hold records cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.hold_id IS DISTINCT FROM OLD.hold_id
            OR NEW.source_service IS DISTINCT FROM OLD.source_service
            OR NEW.record_type IS DISTINCT FROM OLD.record_type
            OR NEW.record_id IS DISTINCT FROM OLD.record_id
            OR NEW.instruction_reference IS DISTINCT FROM OLD.instruction_reference
            OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'legal-hold scope and creation evidence are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status IN ('PENDING_BLOCK', 'CONFLICTED')
            AND NEW.status = 'RELEASE_PENDING'
            AND event_type_value = 'HOLD_RELEASE_REQUESTED' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'RELEASE_PENDING'
            AND NEW.status = 'RELEASED'
            AND event_type_value = 'HOLD_RELEASE_VERIFIED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid legal-hold lifecycle transition'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER lifecycle_legal_hold_tracked_mutation
BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_legal_hold
FOR EACH ROW
EXECUTE FUNCTION lifecycle_legal_hold_reject_untracked_mutation();

CREATE TRIGGER lifecycle_legal_hold_no_truncate
BEFORE TRUNCATE ON lifecycle_legal_hold
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_control_event_reject_mutation();

REVOKE ALL PRIVILEGES ON TABLE lifecycle_legal_hold FROM PUBLIC, roomiq_rlc_app;
GRANT SELECT, INSERT, UPDATE ON TABLE lifecycle_legal_hold TO roomiq_rlc_app;
REVOKE ALL PRIVILEGES ON FUNCTION lifecycle_legal_hold_reject_untracked_mutation()
    FROM PUBLIC, roomiq_rlc_app;
