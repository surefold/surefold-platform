--liquibase formatted sql

-- surefold-openfeature-postgres's table on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/openfeature-postgres/postgres.sql"/>`). Grants are the service's: select to the
-- application's role, and writes to the role its admin endpoint uses.
-- The same SQL ships for Flyway as flyway/V<n>__<changeset>.sql beside this file: a new changeset here is a new
-- script there, and surefold-app-flyway's SurefoldSchemaSuite fails until both leave the same catalog.

--changeset surefold:feature-flags
-- A flag's value per tenant (spec 0240): a null tenant_id is the row every tenant gets, and a tenant's own row is
-- read before it. A tenant reads its own rows and the null ones, and never another tenant's.
create table surefold_feature_flags (
    key        text        not null,
    tenant_id  text,
    value      text        not null,
    reason     text        not null,
    updated_by text        not null,
    updated_at timestamptz not null
);
create unique index surefold_feature_flags_key on surefold_feature_flags (key, coalesce(tenant_id, ''));
alter table surefold_feature_flags enable row level security;
alter table surefold_feature_flags force row level security;
create policy surefold_feature_flags_read on surefold_feature_flags for select
    using (tenant_id is null or tenant_id = current_setting('app.tenant_id', true));
