--liquibase formatted sql

-- surefold-tenancy-crypto's table on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/tenancy-crypto/postgres.sql"/>`), after surefold-tenancy's. Grants are the service's.
-- The same SQL ships for Flyway as flyway/V<n>__<changeset>.sql beside this file: a new changeset here is a new
-- script there, and surefold-app-flyway's SurefoldSchemaSuite fails until both leave the same catalog.

--changeset surefold:tenant-keys
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

--changeset surefold:tenant-keys-no-blank-tenant
-- A connection a tenant transaction has used reads app.tenant_id as '', not null, once that transaction ends. The
-- platform's keys are kept under tenant_id '', so the policy must never match it: only BYPASSRLS reads them.
alter policy tenant_isolation on surefold_tenant_keys
    using (tenant_id <> '' and tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id <> '' and tenant_id = current_setting('app.tenant_id', true));
