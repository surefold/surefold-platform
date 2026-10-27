# Tenancy

`surefold-tenancy` makes Postgres enforce whose rows a transaction sees. Every tenant's rows live in shared tables
under a row-level security policy, every transaction says whose it is, and a transaction that says nothing is refused
before it reaches the database. Work that must cross tenants takes a separate, audited path.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-tenancy:0.13.0")
    testImplementation("io.github.matthewjones372:surefold-tenancy-test:0.13.0")
}
```

## A tenant, carried

```kotlin
withTenant(TenantId("acme")) {
    currentTenant()                              // TenantId(acme), here and in every fork opened here
    Jobs.enqueue(connection, SendReminder, …)    // the tenant rides the job, and is bound again when it runs
}
```

The tenant is a `SurefoldLocal`, so a `parMap`, a stream on the forks backend and any other fork see it. A registered
`Carrier` carries it across a `tell` to an actor and into an enqueued job, beside the trace.

## A policy per table

The policies are the service's, in its own changelog. Every table with tenants' rows gets the same three statements:

<!-- tenancy-changelog -->
```sql
--liquibase formatted sql

--changeset example:bookings
create table bookings (
    id        bigint generated always as identity primary key,
    -- Filled from the transaction's tenant, so an insert cannot name another.
    tenant_id text   not null default current_setting('app.tenant_id', true),
    name      text   not null
);
create index bookings_tenant on bookings (tenant_id);

alter table bookings enable row level security;
-- Without this, the table's owner, which a migration often runs as, would see every tenant's rows.
alter table bookings force row level security;

create policy tenant_isolation on bookings
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
```

- `force row level security` matters: without it, the table's owner sees every row, and a migration, or a service,
  often connects as the owner.
- `current_setting('app.tenant_id', true)` answers null when nothing set it, and a null matches no row, so a
  connection that skipped `TenantTransactions` sees nothing rather than everything.
- The application's role must be neither a superuser nor `BYPASSRLS`. Its connections are the ones the policy binds.

Include surefold's own changelog for the audit table too: `<include file="surefold/tenancy/postgres.sql"/>`.

## Transactions

```kotlin
val db = TenantTransactions(app = appDataSource, system = systemDataSource)

db.transaction { connection -> … }               // as the bound tenant, or Left(NoTenant) with none bound
db.transaction(TenantId("acme")) { connection -> … }
db.system(actor = "jobs-worker", reason = "claim every tenant's jobs") { connection -> … }
```

`transaction` runs `select set_config('app.tenant_id', ?, true)` in the transaction it opens, which lasts until that
transaction ends as `SET LOCAL` does, so a pooled connection carries no tenant to its next user. It commits when the
block returns and rolls back when it throws.

`system` is the one way across tenants. It runs on a second `DataSource` whose role has `BYPASSRLS`, so no SQL the
application runs on its own connections can switch the policies off. Each call writes who ran it and why to
`surefold_tenancy_audit`, in the same transaction, and logs a line.

```sql
create role booking_app login password '…';                     -- the application's role: the policies bind it
create role booking_system login password '…' bypassrls;        -- the system path's role
grant insert on surefold_tenancy_audit to booking_system;
```

## Testing isolation

`checkIsolated` from `surefold-tenancy-test` writes rows as two tenants and reads as each, and fails if either sees a
row of the other's, or cannot see its own, which would mean the check proved nothing:

```kotlin
checkIsolated(
    TenantTransactions(appDataSource),
    insert = { connection, tenant -> connection.prepareStatement("insert into bookings (name) values ('x')").use { it.executeUpdate() } },
    read = { connection -> connection.createStatement().use { s -> s.executeQuery("select tenant_id from bookings").use { rows -> generateSequence { if (rows.next()) TenantId(rows.getString(1)) else null }.toList() } } },
)
```

Run it once per table with tenants' rows, against a database migrated as production is, connecting as the
application's role.
