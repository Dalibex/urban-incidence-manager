-- The history of an incident is never rewritten (AGENTS.md, "Invariants"; #180). The application
-- already has no way to change an entry; this also stops native SQL, a bulk query or a manual
-- session from doing it by mistake, as V8 does for audit_log.
--
-- Unlike audit_log, DELETE and TRUNCATE stay allowed: the datasets empty status_change with
-- incident before they load it again, and an incident is never deleted by the application, so
-- removing its history means removing the incident too. Like V8, it does not stop a superuser or
-- the table owner, who can disable the trigger.
CREATE FUNCTION status_change_reject_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'status_change is never rewritten: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER status_change_unchangeable
    BEFORE UPDATE ON status_change
    FOR EACH ROW EXECUTE FUNCTION status_change_reject_update();
