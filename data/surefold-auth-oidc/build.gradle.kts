// OpenID Connect sign-in (spec 0228): surefold-auth's OidcSignIn on Nimbus's OAuth 2.0 and OpenID Connect SDK, which
// brings its JOSE library and json-smart. A module of its own, so a service that signs in no other way carries neither.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-auth"))
    implementation("com.nimbusds:oauth2-oidc-sdk:11.37.2")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.auth.oidc.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
