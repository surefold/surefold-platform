# Surefold platform

The application-platform modules built on Surefold: auth and OIDC, tenancy and tenancy crypto, files, jobs, caches on Postgres and Redis, idempotency keys, rate limits, flags on Postgres and Postgres notifications; the `surefold-bank` example; the `surefold-jobs-load` benchmark; and `surefold-bom`, the one version a service takes for all of Surefold.

This repository is part of Surefold, which is three repositories:

| Repository | Holds |
|---|---|
| [`surefold-core`](https://github.com/surefold/surefold-core) | structured concurrency, typed errors, resources, sagas, streams, actors and the cluster, on the JDK and Kotlin alone |
| [`surefold-integrations`](https://github.com/surefold/surefold-integrations) | one module per outside library |
| [`surefold-platform`](https://github.com/surefold/surefold-platform) | platform features, the example application and `surefold-bom` |

## Status

Empty until its seed lands: the modules still live in `surefold-core` and move here with their history under
spec 0301 in `surefold-core`. Coordinates and packages do not change: every artifact stays `dev.surefold:<name>` and every
package `dev.surefold.*`.

Nothing is on Maven Central yet. Builds use pinned snapshots from GitHub Packages, and the first release of each
repository comes once all three are proven to work together.

## Depending on Surefold

Take one version for everything through the BOM (spec 0306), rather than picking three by hand:

```kotlin
dependencies {
    implementation(platform("dev.surefold:surefold-bom:<version>"))
    implementation("dev.surefold:surefold")
}
```
