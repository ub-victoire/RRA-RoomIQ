CREATE OR REPLACE FUNCTION public.audit_event_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is prohibited', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER audit_event_no_update_or_delete
BEFORE UPDATE OR DELETE ON public.audit_event
FOR EACH ROW
EXECUTE FUNCTION public.audit_event_reject_mutation();

CREATE TRIGGER audit_event_no_truncate
BEFORE TRUNCATE ON public.audit_event
FOR EACH STATEMENT
EXECUTE FUNCTION public.audit_event_reject_mutation();

GRANT USAGE ON SCHEMA public TO roomiq_audit_app;
REVOKE ALL PRIVILEGES ON TABLE public.audit_event FROM PUBLIC, roomiq_audit_app;
GRANT SELECT, INSERT ON TABLE public.audit_event TO roomiq_audit_app;
