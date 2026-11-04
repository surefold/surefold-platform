-- Every transaction run across tenants (spec 0225): who ran it, why, and when. Written in that transaction, so a
-- system transaction that rolls back leaves no row, and one that commits always does.
create table surefold_tenancy_audit (
    id     bigint      generated always as identity primary key,
    actor  text        not null,
    reason text        not null,
    at     timestamptz not null
);
create index surefold_tenancy_audit_at on surefold_tenancy_audit (at);
