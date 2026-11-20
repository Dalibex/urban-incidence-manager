-- The history of an incident is never rewritten or cut (AGENTS.md, "Invariants"; #180). The
-- application already has no way to change or delete an entry; this also stops native SQL, a bulk
-- query or a manual session from doing it by mistake, as V8 does for audit_log.
--
-- Unlike audit_log, TRUNCATE stays allowed: the datasets empty status_change together with
-- incident before they load it again, and a row trigger does not see a TRUNCATE. Like V8, it does
-- not stop a superuser or the table owner, who can disable the triggers.
CREATE FUNCTION status_change_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'status_change is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER status_change_unchangeable
    BEFORE UPDATE OR DELETE ON status_change
    FOR EACH ROW EXECUTE FUNCTION status_change_reject_change();
