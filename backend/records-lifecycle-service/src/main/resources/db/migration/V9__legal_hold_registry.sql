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
        'HOLD_SUBMITTED',
        'HOLD_APPROVED',
        'HOLD_REJECTED',
        'HOLD_AMENDED',
        'HOLD_RELEASE_REQUESTED',
        'HOLD_RELEASED'
    ));

CREATE TABLE lifecycle_legal_hold (
    hold_id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    hold_reference VARCHAR(128) NOT NULL UNIQUE,
    reason VARCHAR(1000) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    source_service VARCHAR(64),
    record_type VARCHAR(64),
    record_id VARCHAR(128),
    category_code VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    initiating_actor UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE,
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    release_requested_by UUID,
    release_requested_at TIMESTAMP WITH TIME ZONE,
    release_request_evidence_reference VARCHAR(256),
    released_by UUID,
    released_at TIMESTAMP WITH TIME ZONE,
    release_verification_reference VARCHAR(256),
    supporting_evidence_reference VARCHAR(256) NOT NULL,
    last_evidence_reference VARCHAR(256) NOT NULL,
    correlation_id VARCHAR(128),
    last_event_id UUID NOT NULL REFERENCES lifecycle_control_event (event_id),
    CONSTRAINT ck_lifecycle_legal_hold_version CHECK (version > 0),
    CONSTRAINT ck_lifecycle_legal_hold_reference CHECK (length(btrim(hold_reference)) > 0),
    CONSTRAINT ck_lifecycle_legal_hold_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_lifecycle_legal_hold_evidence CHECK (
        length(btrim(supporting_evidence_reference)) > 0
        AND length(btrim(last_evidence_reference)) > 0
    ),
    CONSTRAINT ck_lifecycle_legal_hold_scope CHECK (
        (scope_type = 'RECORD'
            AND source_service IS NOT NULL AND record_type IS NOT NULL AND record_id IS NOT NULL
            AND category_code IS NULL)
        OR
        (scope_type = 'CATEGORY'
            AND source_service IS NULL AND record_type IS NULL AND record_id IS NULL
            AND category_code IS NOT NULL)
    ),
    CONSTRAINT ck_lifecycle_legal_hold_status CHECK (
        status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'RELEASE_PENDING', 'RELEASED', 'REJECTED')
    ),
    CONSTRAINT ck_lifecycle_legal_hold_effective CHECK (
        (status IN ('DRAFT', 'PENDING_APPROVAL', 'REJECTED') AND effective_from IS NULL
            AND approved_by IS NULL AND approved_at IS NULL)
        OR
        (status IN ('ACTIVE', 'RELEASE_PENDING', 'RELEASED') AND effective_from IS NOT NULL
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CONSTRAINT ck_lifecycle_legal_hold_release_state CHECK (
        (status IN ('RELEASE_PENDING', 'RELEASED') AND release_requested_by IS NOT NULL
            AND release_requested_at IS NOT NULL AND release_request_evidence_reference IS NOT NULL)
        OR
        (status NOT IN ('RELEASE_PENDING', 'RELEASED') AND release_requested_by IS NULL
            AND release_requested_at IS NULL AND release_request_evidence_reference IS NULL)
    ),
    CONSTRAINT ck_lifecycle_legal_hold_released_state CHECK (
        (status = 'RELEASED' AND released_by IS NOT NULL AND released_at IS NOT NULL
            AND release_verification_reference IS NOT NULL)
        OR
        (status <> 'RELEASED' AND released_by IS NULL AND released_at IS NULL
            AND release_verification_reference IS NULL)
    ),
    CONSTRAINT ck_lifecycle_legal_hold_actor_separation CHECK (
        (approved_by IS NULL OR approved_by <> initiating_actor)
        AND (released_by IS NULL OR released_by <> initiating_actor)
    )
);

CREATE INDEX ix_lifecycle_legal_hold_record_scope
    ON lifecycle_legal_hold (source_service, record_type, record_id, status)
    WHERE scope_type = 'RECORD';

CREATE INDEX ix_lifecycle_legal_hold_category_scope
    ON lifecycle_legal_hold (category_code, status)
    WHERE scope_type = 'CATEGORY';

CREATE INDEX ix_lifecycle_legal_hold_status_updated
    ON lifecycle_legal_hold (status, updated_at DESC);

CREATE FUNCTION lifecycle_legal_hold_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_event_id TEXT;
    event_row lifecycle_control_event%ROWTYPE;
    expected_event_type VARCHAR(48);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'legal hold records cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    configured_event_id := NULLIF(current_setting('roomiq.lifecycle.event_id', TRUE), '');
    IF configured_event_id IS NULL OR NEW.last_event_id::TEXT <> configured_event_id THEN
        RAISE EXCEPTION 'legal hold mutation requires an audited lifecycle event in the same transaction'
            USING ERRCODE = '55000';
    END IF;

    SELECT * INTO event_row
    FROM lifecycle_control_event
    WHERE event_id = configured_event_id::UUID;
    IF NOT FOUND OR event_row.aggregate_type <> 'HOLD'
            OR event_row.aggregate_id <> NEW.hold_id THEN
        RAISE EXCEPTION 'legal hold lifecycle event is missing or invalid'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        expected_event_type := 'HOLD_CREATED';
        IF NEW.version <> 1 OR NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'legal holds must be created as version-one drafts'
                USING ERRCODE = '55000';
        END IF;
    ELSE
        IF NEW.hold_id <> OLD.hold_id OR NEW.hold_reference <> OLD.hold_reference
                OR NEW.initiating_actor <> OLD.initiating_actor OR NEW.created_at <> OLD.created_at
                OR NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'legal hold identity is immutable and updates must advance one version'
                USING ERRCODE = '55000';
        END IF;
        IF OLD.status = 'DRAFT' AND NEW.status = 'PENDING_APPROVAL' THEN
            expected_event_type := 'HOLD_SUBMITTED';
        ELSIF OLD.status = 'PENDING_APPROVAL' AND NEW.status = 'ACTIVE' THEN
            expected_event_type := 'HOLD_APPROVED';
        ELSIF OLD.status = 'PENDING_APPROVAL' AND NEW.status = 'REJECTED' THEN
            expected_event_type := 'HOLD_REJECTED';
        ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'ACTIVE' THEN
            expected_event_type := 'HOLD_AMENDED';
        ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'RELEASE_PENDING' THEN
            expected_event_type := 'HOLD_RELEASE_REQUESTED';
        ELSIF OLD.status = 'RELEASE_PENDING' AND NEW.status = 'RELEASED' THEN
            expected_event_type := 'HOLD_RELEASED';
        ELSE
            RAISE EXCEPTION 'unsupported legal hold state transition'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF event_row.event_type IS DISTINCT FROM expected_event_type
            OR event_row.actor_subject IS DISTINCT FROM NEW.updated_by::TEXT
            OR event_row.metadata ->> 'operation' IS DISTINCT FROM expected_event_type
            OR event_row.metadata ->> 'outcome' IS DISTINCT FROM 'SUCCEEDED'
            OR event_row.metadata ->> 'resourceType' IS DISTINCT FROM 'LEGAL_HOLD'
            OR event_row.metadata ->> 'resourceId' IS DISTINCT FROM NEW.hold_id::TEXT
            OR event_row.metadata ->> 'version' IS DISTINCT FROM NEW.version::TEXT
            OR event_row.metadata -> 'after' IS NULL
            OR event_row.metadata -> 'after' ->> 'holdId' IS DISTINCT FROM NEW.hold_id::TEXT
            OR event_row.metadata -> 'after' ->> 'version' IS DISTINCT FROM NEW.version::TEXT
            OR event_row.metadata -> 'after' ->> 'holdReference' IS DISTINCT FROM NEW.hold_reference
            OR event_row.metadata -> 'after' ->> 'reason' IS DISTINCT FROM NEW.reason
            OR event_row.metadata -> 'after' ->> 'status' IS DISTINCT FROM NEW.status
            OR event_row.metadata -> 'after' ->> 'initiatingActor' IS DISTINCT FROM NEW.initiating_actor::TEXT
            OR event_row.metadata -> 'after' ->> 'updatedBy' IS DISTINCT FROM NEW.updated_by::TEXT
            OR event_row.metadata -> 'after' ->> 'lastEventId' IS DISTINCT FROM NEW.last_event_id::TEXT
            OR event_row.metadata -> 'after' ->> 'supportingEvidenceReference'
                IS DISTINCT FROM NEW.supporting_evidence_reference
            OR event_row.metadata -> 'after' ->> 'lastEvidenceReference'
                IS DISTINCT FROM NEW.last_evidence_reference
            OR event_row.metadata -> 'after' ->> 'correlationId' IS DISTINCT FROM NEW.correlation_id
            OR event_row.metadata -> 'after' -> 'scope' ->> 'type' IS DISTINCT FROM NEW.scope_type
            OR event_row.metadata -> 'after' -> 'scope' ->> 'sourceService' IS DISTINCT FROM NEW.source_service
            OR event_row.metadata -> 'after' -> 'scope' ->> 'recordType' IS DISTINCT FROM NEW.record_type
            OR event_row.metadata -> 'after' -> 'scope' ->> 'recordId' IS DISTINCT FROM NEW.record_id
            OR event_row.metadata -> 'after' -> 'scope' ->> 'categoryCode' IS DISTINCT FROM NEW.category_code THEN
        RAISE EXCEPTION 'legal hold mutation does not match its lifecycle evidence'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE' AND (
            event_row.metadata -> 'before' ->> 'holdId' IS DISTINCT FROM OLD.hold_id::TEXT
            OR event_row.metadata -> 'before' ->> 'version' IS DISTINCT FROM OLD.version::TEXT
            OR event_row.metadata -> 'before' ->> 'status' IS DISTINCT FROM OLD.status
            OR event_row.metadata -> 'before' ->> 'lastEventId' IS DISTINCT FROM OLD.last_event_id::TEXT
            OR event_row.metadata -> 'before' -> 'scope' ->> 'type' IS DISTINCT FROM OLD.scope_type
            OR event_row.metadata -> 'before' -> 'scope' ->> 'sourceService' IS DISTINCT FROM OLD.source_service
            OR event_row.metadata -> 'before' -> 'scope' ->> 'recordType' IS DISTINCT FROM OLD.record_type
            OR event_row.metadata -> 'before' -> 'scope' ->> 'recordId' IS DISTINCT FROM OLD.record_id
            OR event_row.metadata -> 'before' -> 'scope' ->> 'categoryCode' IS DISTINCT FROM OLD.category_code
        ) THEN
        RAISE EXCEPTION 'legal hold prior-state evidence does not match the previous version'
            USING ERRCODE = '55000';
    END IF;

    PERFORM set_config('roomiq.lifecycle.event_id', '', TRUE);
    RETURN NEW;
END;
$$;

CREATE TRIGGER lifecycle_legal_hold_controlled_mutation
BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_legal_hold
FOR EACH ROW
EXECUTE FUNCTION lifecycle_legal_hold_guard();

CREATE FUNCTION lifecycle_legal_hold_reject_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'legal hold records cannot be truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER lifecycle_legal_hold_no_truncate
BEFORE TRUNCATE ON lifecycle_legal_hold
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_legal_hold_reject_truncate();

GRANT SELECT, INSERT, UPDATE ON TABLE lifecycle_legal_hold TO roomiq_rlc_app;
REVOKE ALL PRIVILEGES ON FUNCTION
    lifecycle_legal_hold_guard(),
    lifecycle_legal_hold_reject_truncate()
FROM PUBLIC, roomiq_rlc_app;
