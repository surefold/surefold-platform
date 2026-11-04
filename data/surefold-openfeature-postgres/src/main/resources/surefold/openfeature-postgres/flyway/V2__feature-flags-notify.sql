-- Every change to a row, by any writer, sends its tenant on surefold_feature_flags, or '' for a row every tenant gets
-- (spec 0240), so a provider listening drops what it holds for that tenant before its ttl runs out.
create function surefold_feature_flags_notify() returns trigger language plpgsql as $fn$
begin
    if tg_op in ('UPDATE', 'DELETE') then
        perform pg_notify('surefold_feature_flags', coalesce(old.tenant_id, ''));
    end if;
    if tg_op in ('INSERT', 'UPDATE') then
        perform pg_notify('surefold_feature_flags', coalesce(new.tenant_id, ''));
    end if;
    return null;
end
$fn$;
create trigger surefold_feature_flags_notify after insert or update or delete on surefold_feature_flags
    for each row execute function surefold_feature_flags_notify();
