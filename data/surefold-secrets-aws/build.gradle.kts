// surefold-secrets on AWS Secrets Manager and SSM Parameter Store (spec 0046). The SDK runs on the JDK's HTTP client,
// so neither Netty nor Apache's client comes with it. NoOtherDependenciesTest asserts the runtime classpath.
val awsVersion = "2.55.6"

dependencies {
    api(project(":surefold-secrets"))
    api("software.amazon.awssdk:secretsmanager:$awsVersion") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    api("software.amazon.awssdk:ssm:$awsVersion") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:$awsVersion")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.secrets.aws.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
