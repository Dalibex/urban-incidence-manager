-- The audit trail is append-only (RF18, #36). The application already has no way to change an
-- entry; this also stops native SQL, a bulk query or a manual session from doing it by mistake.
-- It does not stop a superuser or the table owner, who can disable the triggers: that needs
-- separate database roles.
CREATE FUNCTION audit_log_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();

-- TRUNCATE does not fire row triggers, so it needs a statement trigger of its own.
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_change();
