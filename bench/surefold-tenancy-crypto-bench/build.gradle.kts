/**
 * What one use of a held key view costs, alone and beside seven neighbours on the same key (spec 0004). Nothing here
 * is published, and nothing in `build` runs it: `./gradlew :surefold-tenancy-crypto-bench:run`, optionally with
 * `--args="<uses per caller>"`.
 */

plugins {
    application
}

dependencies {
    implementation(project(":surefold-tenancy-crypto"))
}

application {
    mainClass.set("dev.surefold.tenancy.crypto.bench.KeyViewUseKt")
}
