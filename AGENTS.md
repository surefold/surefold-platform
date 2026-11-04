# Working in this repo

## Where this repository sits

Surefold is three repositories, each depending only on the ones above it, through published artifacts:

```
surefold-core          JDK + Kotlin only at runtime
      ▲
surefold-integrations  core + one outside library per module
      ▲
surefold-platform      core + integrations, and surefold-bom
```

This is `surefold-platform`. It may depend on `surefold-core` and `surefold-integrations`, through their published artifacts. Nothing builds on this repository; it is the furthest downstream, which is why it publishes the BOM.

## Until the seed lands

This repository is empty on purpose. Its modules still live in `surefold-core`, and spec 0301 in `surefold-core` moves them here
with their history, in its stack's order. Until then:

- **Don't add modules, code or a build here by hand.** The seed entry brings the build, its gates and CI across.
- **Work on these modules happens in `surefold-core`**, under that repository's `AGENTS.md`.

## Rules once it has landed

The way of working is `surefold-core`'s, and the seed copies its `AGENTS.md` and `CODING_STANDARDS.md` here: specs
first, one spec section per pull request, the failing test first, `./gradlew spotlessApply && ./gradlew build`
before saying anything is done, and the same gates (detekt, `NoOtherDependenciesTest`, Kover at 90%). On top of
those:

- **Upstream comes from GitHub Packages, pinned.** `gradle.properties` names the exact snapshot versions of each
  upstream repository, read from `https://maven.pkg.github.com/surefold/<repository>`. Moving to a newer upstream is
  a one-line pull request that bumps the pin and is green. No Surefold POM uses a version range.
- **Coordinates never change.** Artifacts stay `dev.surefold:<name>`, packages stay `dev.surefold.*`.
- **`surefold-bom` names only what was tested** (spec 0306): exactly the core and integrations versions pinned in `gradle.properties` and built against here, plus this repository's modules. A module in the BOM that no test here depends on fails the build.
- **surefold-bank is rewritten on the durable saga** (spec 0299) once a core snapshot with it exists: `Transfer.kt` deleted, `Account.kt` on `keyed`, `SagaSuite` and `CrashSuite` passing.
- **Working across repositories:** check the repositories out side by side (`../surefold-core` and `../surefold-integrations`). The build includes
  the upstream ones it finds there, and Gradle uses their source in place of the pinned artifacts, so a change
  upstream reaches this repository's tests without publishing. CI never checks them out: it always builds against
  the pins.
- **Specs** live in `specs/` here, numbered from 0001. Specs about this repository's modules written before the
  split stay in `surefold-core` as the record; link them rather than copying.
- **No Central release** until all three repositories are proven together. Snapshots publish on every push to main.
