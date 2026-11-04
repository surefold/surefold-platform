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

This is `surefold-platform`. It may depend on `surefold-core` and `surefold-integrations`, through their published
  artifacts. Nothing builds on this repository; it is the furthest downstream, which is why it publishes the BOM.

## Rules on top of core's

The way of working is `surefold-core`'s, set out in the sections below: specs first, one spec section per pull
request, the failing test first, `./gradlew spotlessApply && ./gradlew build` before saying anything is done, and the
same gates (detekt, `NoOtherDependenciesTest`, Kover at 90%). On top of those:

- **Upstream comes from GitHub Packages, pinned.** `gradle.properties` names the exact snapshot versions of each
  upstream repository, read from `https://maven.pkg.github.com/surefold/<repository>`. Moving to a newer upstream is
  a one-line pull request that bumps the pin and is green. No Surefold POM uses a version range.
- **Coordinates never change.** Artifacts stay `dev.surefold:<name>`, packages stay `dev.surefold.*`.
- **`surefold-bom` names only what was tested** (spec 0306): exactly the core and integrations versions pinned in
  `gradle.properties` and built against here, plus this repository's modules. A module in the BOM that no test here
  depends on fails the build.
- **surefold-bank is rewritten on the durable saga** (spec 0299) once a core snapshot with it exists: `Transfer.kt`
  deleted, `Account.kt` on `keyed`, `SagaSuite` and `CrashSuite` passing.
- **Working across repositories:** check the repositories out side by side (`../surefold-core` and
  `../surefold-integrations`). The build includes
  the upstream ones it finds there, and Gradle uses their source in place of the pinned artifacts, so a change
  upstream reaches this repository's tests without publishing. CI never checks them out: it always builds against
  the pins.
- **Specs** live in `specs/` here, numbered from 0001. Specs about this repository's modules written before the
  split stay in `surefold-core` as the record; link them rather than copying.
- **No Central release** until all three repositories are proven together. Snapshots publish on every push to main.

## Specs come first

Nothing is implemented without a spec file in `specs/`. An agent drafts it, a
human edits it, and only the edited, committed version gets built.

The draft is a proposal, not a plan. Its job is to be **cheap to disagree
with**, so it stays short enough to read in one sitting:

- **One page.** Roughly 80 lines. A draft that runs longer is proposing too
  much at once — split it into two specs rather than writing more.
- **No prose padding.** Fill the template's headings and stop. Where a heading
  has nothing real under it, write "nothing" and move on.
- **Uncertainty goes under Open questions**, not into a hedged paragraph under
  Shape. Three or four questions in a first draft is healthy.
- **Options, not verdicts.** Where a design could go two ways, give each a
  sentence and recommend one. Do not silently pick.

Then stop and hand it over. Do not implement a spec nobody has edited and
committed: an unreviewed draft is still the agent's own opinion, which is the
thing this process exists to stop.

Before a spec exists: ask questions, read code, answer in chat. No code.

`specs/README.md` gives the layout and the lifecycle. Specs here are numbered from 0001.

## One spec section per pull request

Pull requests are stacked. Each branch sits on the one before it and is
reviewable on its own.

- **Soft cap: 200 changed lines** of main sources, build files and docs. Tests,
  generated sources and golden fixtures do not count. Past that, split before
  writing code rather than after.
- **One spec section per PR.** A spec with four stack entries is four branches,
  not one branch with four commits.
- **Announce the split first.** Post the intended stack — branch name and one
  line each — and wait for a yes before the first commit.

Stacking commands (`gh stack init`, `add`, `submit --open`, fixing a lower
entry): surefold-core's [`docs/agents/stacking.md`](https://github.com/surefold/surefold-core/blob/main/docs/agents/stacking.md).

## Layout

Modules keep the folders they had in surefold-core (`app/`, `bench/`, `data/`, `examples/`) and their flat Gradle
paths, `:surefold-jobs` rather than `:data:surefold-jobs`; `settings.gradle.kts` says which folder each is in. A new
module goes in its family's folder and gets a `module(...)` line there.

Every module depends on the surefold-core modules it builds on by coordinates at the pinned `surefoldCore`
(`"dev.surefold:surefold-actor:$surefoldCore"`), on surefold-integrations' at `surefoldIntegrations`, on other
modules here by `project(":…")`, and on the outside libraries its name and its spec give it.

Each claim is a test. `NoOtherDependenciesTest` asserts the module's main runtime classpath, in every module alike.

A dependency added to any module is a build failure, not a judgement call.

## Coding standards

Comments, imports, errors, effects and read-only API:
[`CODING_STANDARDS.md`](CODING_STANDARDS.md). Review enforces it.

## Testing

Write the failing test first. Most claims here are claims about agreement
between the route, the document, the test client and the generated client, and
a test written afterwards asserts what the implementation does rather than what
the description promised.

Test names are sentences in backticks. `surefold-assert` matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

Work out which of these a change can break:

- **Thread claims.** A test that says the body ran on a virtual thread reads
  `Thread.currentThread().isVirtual` inside the body and asserts on it
  afterwards; it does not count threads or time sleeps.

### Examples in the docs are compiled

The guides' fences are held to the code by suites that read the Markdown: `docs/jobs.md` (surefold-app-jobs),
`auth.md` (surefold-auth), `files.md` (surefold-files-test), `http-stores.md` (surefold-idempotency),
`postgres-notify.md` (surefold-postgres-notify), `tenancy.md` (surefold-tenancy-test), `flags-per-tenant.md`
(surefold-openfeature-postgres) and `flyway.md` (surefold-app-flyway). A fence is found by the HTML comment above it, so keep the marker when editing an example, and
add one to a new complete example. Each module's `build.gradle.kts` declares the pages it reads as test inputs.

Kover has a floor of 90% on `check`.

## Verifying

`./gradlew build` runs tests, detekt and spotless. Run it before saying
anything is done, and quote the result rather than predicting it.

**Finish with `./gradlew spotlessApply`.** Formatting is a gate like any other,
and a change that is correct but unformatted fails the build for whoever runs
it next. Run it last, after the final edit, so nothing lands unformatted:

```bash
./gradlew spotlessApply && ./gradlew build
```

CI builds the whole repository on every pull request and push, against the pinned upstream snapshots.

One Gradle build at a time per checkout. Two at once fail with timeouts and
missing test results that look like real failures.

Three gates sit beyond the tests. Each exists because a claim in the README
would otherwise be unverified.

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding, or a source it cannot compile (`X.serializer()`: write `serializer<X>()`) | a suppression with no reason |
| `NoOtherDependenciesTest` | anything but what the module is allowed on the runtime classpath | widening the allow-list |
| Kover | line coverage under 90% | lowering the floor |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- No outside library beyond what a module's name and spec give it.
- The README reflects the change, or the change is invisible from outside.

## Issue tracker

Specs are the tickets: a spec's Stack entries are its work items, in blocking order. Other issues live in GitHub
Issues for `surefold/surefold-platform`.
