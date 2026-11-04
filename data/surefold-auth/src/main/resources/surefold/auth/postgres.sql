--liquibase formatted sql

-- surefold-auth's tables on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/auth/postgres.sql"/>`). Nothing creates them at start.
-- The same SQL ships for Flyway as flyway/V<n>__<changeset>.sql beside this file: a new changeset here is a new
-- script there, and surefold-app-flyway's SurefoldSchemaSuite fails until both leave the same catalog.

--changeset surefold:auth-sessions
-- One row per session (spec 0227). The token itself is never stored, only its SHA-256, so the table signs no one in.
create table surefold_sessions (
    id             uuid        primary key,
    token_hash     bytea       not null unique,
    user_id        text        not null,
    tenant_id      text,
    created_at     timestamptz not null,
    last_seen_at   timestamptz not null,
    idle_until     timestamptz not null,
    absolute_until timestamptz not null
);
create index surefold_sessions_user on surefold_sessions (user_id);
create index surefold_sessions_idle on surefold_sessions (idle_until);

--changeset surefold:auth-links
-- One row per magic link issued and not yet redeemed (spec 0227), by its token's SHA-256.
create table surefold_magic_links (
    token_hash bytea       primary key,
    user_id    text        not null,
    expires_at timestamptz not null
);
create index surefold_magic_links_expires on surefold_magic_links (expires_at);

--changeset surefold:auth-roles
-- Roles granted to a user in a tenant, or in every tenant when tenant_id is null, and what each allows (spec 0227).
create table surefold_role_grants (
    user_id   text not null,
    tenant_id text,
    role      text not null,
    unique nulls not distinct (user_id, tenant_id, role)
);
create table surefold_role_permissions (
    role       text not null,
    permission text not null,
    primary key (role, permission)
);

--changeset surefold:auth-totp
-- One TOTP secret per user (spec 0228), AES-GCM encrypted under a key the database never sees, and the last step a
-- code was accepted for, so no code is accepted twice.
create table surefold_totp (
    user_id   text    primary key,
    secret    bytea   not null,
    confirmed boolean not null,
    last_step bigint
);
