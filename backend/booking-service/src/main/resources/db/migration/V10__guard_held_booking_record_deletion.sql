CREATE FUNCTION booking_reject_held_record_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    request_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'booking_request' THEN
        request_id := OLD.id;
    ELSE
        request_id := OLD.booking_request_id;
        PERFORM 1 FROM booking_request WHERE id = request_id FOR UPDATE;
    END IF;
    IF EXISTS (
        SELECT 1 FROM booking_record_legal_hold
        WHERE booking_request_id = request_id AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'booking record is protected by an active records hold'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_booking_record_not_on_active_legal_hold';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER booking_request_active_hold_delete_guard
BEFORE DELETE ON booking_request
FOR EACH ROW EXECUTE FUNCTION booking_reject_held_record_delete();

CREATE TRIGGER reservation_active_hold_delete_guard
BEFORE DELETE ON reservation
FOR EACH ROW EXECUTE FUNCTION booking_reject_held_record_delete();
