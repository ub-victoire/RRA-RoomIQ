CREATE TABLE booking_record_legal_hold (
    id UUID PRIMARY KEY,
    hold_id UUID NOT NULL UNIQUE,
    booking_request_id UUID NOT NULL REFERENCES booking_request(id),
    status VARCHAR(16) NOT NULL,
    instruction_revision BIGINT NOT NULL,
    last_instruction_id UUID NOT NULL,
    last_instruction_digest VARCHAR(64) NOT NULL,
    instruction_reference_sha256 VARCHAR(64) NOT NULL,
    last_correlation_id VARCHAR(128),
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_booking_record_legal_hold_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_booking_record_legal_hold_revision CHECK (instruction_revision > 0),
    CONSTRAINT ck_booking_record_legal_hold_digest CHECK (
        last_instruction_digest ~ '^[0-9a-f]{64}$'
        AND instruction_reference_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_booking_record_legal_hold_release_state CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL))
);

CREATE INDEX ix_booking_record_legal_hold_active_record
    ON booking_record_legal_hold (booking_request_id)
    WHERE status = 'ACTIVE';

CREATE TABLE booking_legal_hold_instruction_receipt (
    receipt_id UUID PRIMARY KEY,
    instruction_id UUID NOT NULL,
    hold_id UUID NOT NULL,
    instruction_revision BIGINT NOT NULL,
    operation VARCHAR(8) NOT NULL,
    booking_request_id UUID NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    instruction_digest CHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    enforcement_status VARCHAR(16),
    booking_request_version BIGINT,
    correlation_id VARCHAR(128),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_booking_hold_receipt_operation CHECK (operation IN ('APPLY', 'RELEASE')),
    CONSTRAINT ck_booking_hold_receipt_revision CHECK (instruction_revision > 0),
    CONSTRAINT ck_booking_hold_receipt_source CHECK (source_service = 'records-lifecycle-service'),
    CONSTRAINT ck_booking_hold_receipt_outcome CHECK (
        outcome IN ('ACCEPTED', 'DUPLICATE', 'STALE', 'CONFLICTED', 'REJECTED',
                    'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_booking_hold_receipt_digest CHECK (instruction_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_booking_hold_receipt_instruction
    ON booking_legal_hold_instruction_receipt (hold_id, instruction_revision, received_at);

CREATE FUNCTION booking_reject_legal_hold_receipt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'booking legal-hold instruction receipts are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER booking_legal_hold_receipt_append_only
BEFORE UPDATE OR DELETE ON booking_legal_hold_instruction_receipt
FOR EACH ROW EXECUTE FUNCTION booking_reject_legal_hold_receipt_mutation();

CREATE TRIGGER booking_legal_hold_receipt_no_truncate
BEFORE TRUNCATE ON booking_legal_hold_instruction_receipt
FOR EACH STATEMENT EXECUTE FUNCTION booking_reject_legal_hold_receipt_mutation();

CREATE FUNCTION booking_guard_legal_hold_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    receipt_digest CHAR(64);
    receipt_outcome VARCHAR(32);
    receipt_operation VARCHAR(8);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT instruction_digest, outcome, operation
          INTO receipt_digest, receipt_outcome, receipt_operation
          FROM booking_legal_hold_instruction_receipt
         WHERE instruction_id = NEW.last_instruction_id
           AND hold_id = NEW.hold_id
           AND instruction_revision = NEW.instruction_revision
         ORDER BY received_at DESC LIMIT 1;
        IF receipt_digest IS NULL OR receipt_digest <> NEW.last_instruction_digest
           OR receipt_outcome <> 'ACCEPTED' OR receipt_operation <> 'APPLY'
           OR NEW.status <> 'ACTIVE' OR NEW.instruction_revision <> 1 THEN
            RAISE EXCEPTION 'legal-hold creation requires matching accepted instruction receipt'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'booking legal holds cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.hold_id IS DISTINCT FROM OLD.hold_id
       OR NEW.booking_request_id IS DISTINCT FROM OLD.booking_request_id
       OR NEW.instruction_revision <> OLD.instruction_revision + 1
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'legal-hold identity is immutable and revisions must be sequential'
            USING ERRCODE = '55000';
    END IF;

    SELECT instruction_digest, outcome, operation
      INTO receipt_digest, receipt_outcome, receipt_operation
      FROM booking_legal_hold_instruction_receipt
     WHERE instruction_id = NEW.last_instruction_id
       AND hold_id = NEW.hold_id
       AND instruction_revision = NEW.instruction_revision
     ORDER BY received_at DESC LIMIT 1;
    IF receipt_digest IS NULL OR receipt_digest <> NEW.last_instruction_digest
       OR receipt_outcome <> 'ACCEPTED' THEN
        RAISE EXCEPTION 'legal-hold transition requires matching accepted instruction receipt'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RELEASED' AND receipt_operation = 'RELEASE' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid booking legal-hold transition'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER booking_legal_hold_tracked_mutation
BEFORE INSERT OR UPDATE OR DELETE ON booking_record_legal_hold
FOR EACH ROW EXECUTE FUNCTION booking_guard_legal_hold_state();

CREATE TRIGGER booking_legal_hold_no_truncate
BEFORE TRUNCATE ON booking_record_legal_hold
FOR EACH STATEMENT EXECUTE FUNCTION booking_reject_legal_hold_receipt_mutation();

CREATE FUNCTION booking_reject_held_record_lifecycle_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'booking_request' THEN
        request_id := OLD.id;
        IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
            RETURN NEW;
        END IF;
    ELSE
        request_id := OLD.booking_request_id;
        IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
            RETURN NEW;
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1 FROM booking_record_legal_hold
        WHERE booking_request_id = request_id AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'booking record is protected by an active records hold'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_booking_record_not_on_active_legal_hold';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER booking_request_active_hold_lifecycle_guard
BEFORE UPDATE OF status ON booking_request
FOR EACH ROW EXECUTE FUNCTION booking_reject_held_record_lifecycle_change();

CREATE TRIGGER reservation_active_hold_lifecycle_guard
BEFORE UPDATE OF status ON reservation
FOR EACH ROW EXECUTE FUNCTION booking_reject_held_record_lifecycle_change();
