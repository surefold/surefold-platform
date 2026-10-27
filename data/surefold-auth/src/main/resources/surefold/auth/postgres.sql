--liquibase formatted sql

-- surefold-auth's tables on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/auth/postgres.sql"/>`). Nothing creates them at start.

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
