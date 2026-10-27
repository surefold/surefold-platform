--liquibase formatted sql

-- surefold-idempotency's table on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/idempotency/postgres.sql"/>`). Nothing creates it at start.
-- The same SQL ships for Flyway as flyway/V<n>__<changeset>.sql beside this file: a new changeset here is a new
-- script there, and surefold-app-flyway's SurefoldSchemaSuite fails until both leave the same catalog.

--changeset surefold:idempotency
-- One row per key a request was made with (spec 0226): who holds it while its handler runs, and the response once it
-- has, until it expires.
create table surefold_idempotency (
    scope           text        not null,
    key             text        not null,
    fingerprint     text        not null,
    status          text        not null check (status in ('running', 'done')),
    -- Whose reservation it is, so a holder whose lease was taken over cannot write over the new one's answer.
    holder          uuid        not null,
    locked_until    timestamptz,
    response_status integer,
    header_names    text[],
    header_values   text[],
    body            bytea,
    expires_at      timestamptz not null,
    created_at      timestamptz not null,
    primary key (scope, key)
);
create index surefold_idempotency_expires on surefold_idempotency (expires_at);
