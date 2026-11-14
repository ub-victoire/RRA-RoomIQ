ALTER TABLE lifecycle_legal_hold
    ALTER COLUMN status TYPE VARCHAR(32),
    DROP CONSTRAINT ck_lifecycle_legal_hold_status,
    ADD CONSTRAINT ck_lifecycle_legal_hold_status
        CHECK (status IN ('PENDING_BLOCK', 'CONFLICTED', 'RELEASE_PENDING',
                          'RELEASE_ENFORCEMENT_PENDING', 'RELEASED')),
    DROP CONSTRAINT ck_lifecycle_legal_hold_release_state,
    ADD CONSTRAINT ck_lifecycle_legal_hold_release_state CHECK (
        (status IN ('PENDING_BLOCK', 'CONFLICTED')
            AND release_requested_by_user_id IS NULL AND release_requested_at IS NULL
            AND release_reference IS NULL AND verified_by_user_id IS NULL
            AND verified_at IS NULL AND verification_reference IS NULL)
        OR (status = 'RELEASE_PENDING'
            AND release_requested_by_user_id IS NOT NULL AND release_requested_at IS NOT NULL
            AND release_reference IS NOT NULL AND verified_by_user_id IS NULL
            AND verified_at IS NULL AND verification_reference IS NULL)
        OR (status IN ('RELEASE_ENFORCEMENT_PENDING', 'RELEASED')
            AND release_requested_by_user_id IS NOT NULL AND release_requested_at IS NOT NULL
            AND release_reference IS NOT NULL AND verified_by_user_id IS NOT NULL
            AND verified_at IS NOT NULL AND verification_reference IS NOT NULL)
    );

ALTER TABLE lifecycle_control_event
    DROP CONSTRAINT ck_lifecycle_control_event_type,
    ADD CONSTRAINT ck_lifecycle_control_event_type
        CHECK (event_type IN (
            'POLICY_CREATED', 'POLICY_RULE_CREATED', 'POLICY_RULE_MODIFIED',
            'POLICY_VALIDATED', 'POLICY_ACTIVATED', 'POLICY_SUPERSEDED',
            'POLICY_WITHDRAWN', 'HOLD_CREATED', 'HOLD_RELEASE_REQUESTED',
            'HOLD_RELEASE_VERIFIED', 'HOLD_OWNER_INSTRUCTION_ACKNOWLEDGED',
            'HOLD_OWNER_RELEASE_ACKNOWLEDGED'
        ));

CREATE TABLE lifecycle_hold_instruction_outbox (
    outbox_id UUID PRIMARY KEY,
    instruction_id UUID NOT NULL UNIQUE,
    hold_id UUID NOT NULL REFERENCES lifecycle_legal_hold(hold_id),
    instruction_revision BIGINT NOT NULL,
    operation VARCHAR(8) NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    record_type VARCHAR(64) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    instruction_reference_sha256 CHAR(64) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(128),
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_acknowledgement_outcome VARCHAR(32),
    acknowledged_enforcement_status VARCHAR(16),
    acknowledged_booking_request_version BIGINT,
    last_error_code VARCHAR(64),
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_lifecycle_hold_instruction_revision UNIQUE (hold_id, instruction_revision),
    CONSTRAINT ck_lifecycle_hold_instruction_operation CHECK (operation IN ('APPLY', 'RELEASE')),
    CONSTRAINT ck_lifecycle_hold_instruction_revision CHECK (instruction_revision > 0),
    CONSTRAINT ck_lifecycle_hold_instruction_scope CHECK (
        source_service = 'booking-service' AND record_type = 'BOOKING_REQUEST'),
    CONSTRAINT ck_lifecycle_hold_instruction_reference_digest CHECK (
        instruction_reference_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_lifecycle_hold_instruction_delivery_status CHECK (
        delivery_status IN ('PENDING', 'PROCESSING', 'ACKNOWLEDGED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_lifecycle_hold_instruction_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_lifecycle_hold_instruction_ack_outcome CHECK (
        last_acknowledgement_outcome IS NULL OR last_acknowledgement_outcome IN (
            'ACCEPTED', 'DUPLICATE', 'STALE', 'CONFLICTED', 'REJECTED',
            'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_lifecycle_hold_instruction_ack_state CHECK (
        acknowledged_enforcement_status IS NULL
        OR acknowledged_enforcement_status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_lifecycle_hold_instruction_terminal_state CHECK (
        (delivery_status = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL
            AND last_acknowledgement_outcome IN ('ACCEPTED', 'DUPLICATE'))
        OR (delivery_status <> 'ACKNOWLEDGED' AND acknowledged_at IS NULL))
);

CREATE INDEX ix_lifecycle_hold_instruction_delivery
    ON lifecycle_hold_instruction_outbox (next_attempt_at, hold_id, instruction_revision)
    WHERE delivery_status IN ('PENDING', 'PROCESSING');

CREATE FUNCTION lifecycle_hold_instruction_outbox_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'legal-hold instructions cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.outbox_id IS DISTINCT FROM OLD.outbox_id
        OR NEW.instruction_id IS DISTINCT FROM OLD.instruction_id
        OR NEW.hold_id IS DISTINCT FROM OLD.hold_id
        OR NEW.instruction_revision IS DISTINCT FROM OLD.instruction_revision
        OR NEW.operation IS DISTINCT FROM OLD.operation
        OR NEW.source_service IS DISTINCT FROM OLD.source_service
        OR NEW.record_type IS DISTINCT FROM OLD.record_type
        OR NEW.record_id IS DISTINCT FROM OLD.record_id
        OR NEW.instruction_reference_sha256 IS DISTINCT FROM OLD.instruction_reference_sha256
        OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
        OR NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'legal-hold instruction provenance is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER lifecycle_hold_instruction_outbox_tracked_mutation
BEFORE UPDATE OR DELETE ON lifecycle_hold_instruction_outbox
FOR EACH ROW EXECUTE FUNCTION lifecycle_hold_instruction_outbox_reject_mutation();

CREATE TRIGGER lifecycle_hold_instruction_outbox_no_truncate
BEFORE TRUNCATE ON lifecycle_hold_instruction_outbox
FOR EACH STATEMENT EXECUTE FUNCTION lifecycle_control_event_reject_mutation();

CREATE OR REPLACE FUNCTION lifecycle_legal_hold_reject_untracked_mutation()
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
            AND NEW.status = 'RELEASE_ENFORCEMENT_PENDING'
            AND event_type_value = 'HOLD_RELEASE_VERIFIED' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'RELEASE_ENFORCEMENT_PENDING'
            AND NEW.status = 'RELEASED'
            AND event_type_value = 'HOLD_OWNER_RELEASE_ACKNOWLEDGED' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid legal-hold lifecycle transition'
        USING ERRCODE = '55000';
END;
$$;

REVOKE ALL PRIVILEGES ON TABLE lifecycle_hold_instruction_outbox FROM PUBLIC, roomiq_rlc_app;
GRANT SELECT, INSERT, UPDATE ON TABLE lifecycle_hold_instruction_outbox TO roomiq_rlc_app;
REVOKE ALL PRIVILEGES ON FUNCTION lifecycle_hold_instruction_outbox_reject_mutation()
    FROM PUBLIC, roomiq_rlc_app;
