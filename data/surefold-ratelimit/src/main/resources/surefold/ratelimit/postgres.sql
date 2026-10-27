--liquibase formatted sql

-- surefold-ratelimit's table on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="surefold/ratelimit/postgres.sql"/>`). Nothing creates it at start.
-- The same SQL ships for Flyway as flyway/V<n>__<changeset>.sql beside this file: a new changeset here is a new
-- script there, and surefold-app-flyway's SurefoldSchemaSuite fails until both leave the same catalog.

--changeset surefold:ratelimit
-- One token bucket per key (spec 0226): what it held when last drawn on, and when. A take refills and draws in one
-- statement on its row, so every instance draws on the same bucket.
create table surefold_rate_limits (
    key         text             primary key,
    tokens      double precision not null,
    refilled_at timestamptz      not null
);
