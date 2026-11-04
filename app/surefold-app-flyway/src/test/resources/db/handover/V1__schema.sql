-- The service's schema as Liquibase left it, from pg_dump --schema-only: what a database migrated by Liquibase
-- already has, and what a new one starts from.
create table orders (
    id    integer primary key,
    total integer
);
