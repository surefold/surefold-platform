# Surefold platform

The application-platform modules built on Surefold: auth and OIDC, tenancy and tenancy crypto, files, jobs, caches on Postgres and Redis, idempotency keys, rate limits, flags on Postgres and Postgres notifications; Flyway migrations for them; the `surefold-bank` example; the `surefold-jobs-load` benchmark; and `surefold-bom`, the one version a service takes for all of Surefold.

This repository is part of Surefold, which is three repositories:

| Repository | Holds |
|---|---|
| [`surefold-core`](https://github.com/surefold/surefold-core) | structured concurrency, typed errors, resources, sagas, streams, actors and the cluster, on the JDK and Kotlin alone |
| [`surefold-integrations`](https://github.com/surefold/surefold-integrations) | one module per outside library |
| [`surefold-platform`](https://github.com/surefold/surefold-platform) | platform features, the example application and `surefold-bom` |

## Status

Seeded from `surefold-core` with each module's history (surefold-core's spec 0301). Coordinates and packages did not
change: every artifact stays `dev.surefold:<name>` and every package `dev.surefold.*`.

Nothing is on Maven Central yet. This repository builds against pinned `surefold-core` and `surefold-integrations`
snapshots from GitHub Packages (`surefoldCore` and `surefoldIntegrations` in `gradle.properties`), and the first
release of each repository comes once all three are proven to work together.

## Building

```bash
./gradlew build
```

GitHub Packages asks for a token even to read a public package: put a classic personal access token with only
`read:packages` in `~/.gradle/gradle.properties` as `githubPackagesUsername` and `githubPackagesPassword`. With
`surefold-core` or `surefold-integrations` checked out beside this repository (`../surefold-core`,
`../surefold-integrations`), the build uses its source instead, and needs no token for it.

## Guides

[Jobs](docs/jobs.md), [auth](docs/auth.md), [files](docs/files.md), [HTTP stores](docs/http-stores.md),
[Postgres notifications](docs/postgres-notify.md), [tenancy](docs/tenancy.md),
[tenancy crypto](docs/tenancy-crypto.md), [KMS operations](docs/kms-operations.md), [flags per tenant](docs/flags-per-tenant.md)
and [Flyway](docs/flyway.md).
The example application is [surefold-bank](examples/surefold-bank).

## Depending on Surefold

Take one version for everything through the BOM (spec 0306), rather than picking three by hand:

```kotlin
dependencies {
    implementation(platform("dev.surefold:surefold-bom:<version>"))
    implementation("dev.surefold:surefold")
}
```
