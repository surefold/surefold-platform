-- A connection a tenant transaction has used reads app.tenant_id as '', not null, once that transaction ends. The
-- platform's keys are kept under tenant_id '', so the policy must never match it: only BYPASSRLS reads them.
alter policy tenant_isolation on surefold_tenant_keys
    using (tenant_id <> '' and tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id <> '' and tenant_id = current_setting('app.tenant_id', true));
