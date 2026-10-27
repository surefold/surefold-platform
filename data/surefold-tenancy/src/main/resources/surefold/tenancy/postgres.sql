--liquibase formatted sql

-- surefold-tenancy's table on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/tenancy/postgres.sql"/>`). The row-level security policies on the service's own tables
-- are the service's to write; docs/tenancy.md has the one to copy.
-- The same SQL ships for Flyway as flyway/V<n>__<changeset>.sql beside this file: a new changeset here is a new
-- script there, and surefold-app-flyway's SurefoldSchemaSuite fails until both leave the same catalog.

--changeset surefold:tenancy-audit
-- Every transaction run across tenants (spec 0225): who ran it, why, and when. Written in that transaction, so a
-- system transaction that rolls back leaves no row, and one that commits always does.
create table surefold_tenancy_audit (
    id     bigint      generated always as identity primary key,
    actor  text        not null,
    reason text        not null,
    at     timestamptz not null
);
create index surefold_tenancy_audit_at on surefold_tenancy_audit (at);
