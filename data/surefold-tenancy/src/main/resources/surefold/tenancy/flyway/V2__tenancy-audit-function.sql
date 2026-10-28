-- The audit row a system function call writes (spec 0237), through a definer function, so the role that calls it needs
-- EXECUTE on this and no grant on the table. Its search_path is pinned to the schema it is made in, wherever that is.
do $do$
begin
    execute format($fn$
        create function surefold_tenancy_audit_write(actor text, reason text) returns void
            language sql security definer set search_path = %I, pg_temp
            as $body$ insert into surefold_tenancy_audit (actor, reason, at) values ($1, $2, now()) $body$
    $fn$, current_schema());
    revoke execute on function surefold_tenancy_audit_write(text, text) from public;
end
$do$;
