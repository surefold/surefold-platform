-- Every owner's data keys (spec 0235), each wrapped under the owner's root key in a KMS, which no backup holds. One
-- version of each purpose is active; older ones open what they wrote until retired. tenant_id is '' for the
-- platform's own keys, which no tenant id can be, so only a role with BYPASSRLS reads them.
create table surefold_tenant_keys (
    tenant_id  text        not null,
    purpose    text        not null check (purpose in ('enc', 'bidx')),
    version    integer     not null check (version >= 1),
    wrapped    bytea       not null,
    root       text        not null,
    state      text        not null check (state in ('active', 'decrypt_only')),
    created_at timestamptz not null,
    primary key (tenant_id, purpose, version)
);
create unique index surefold_tenant_keys_active on surefold_tenant_keys (tenant_id, purpose) where state = 'active';
alter table surefold_tenant_keys enable row level security;
alter table surefold_tenant_keys force row level security;
create policy tenant_isolation on surefold_tenant_keys
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
