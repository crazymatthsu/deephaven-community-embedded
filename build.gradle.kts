// Aggregate tasks only. Everything a module needs comes from a convention plugin in build-logic/.

plugins {
    base
}

@Suppress("UNCHECKED_CAST")
val repos = gradle.extra["dhe.repos"] as Map<String, String> // config/repos/repos.env, set by dhe.repositories
val wrapperGradleVersion = libs.versions.gradle.get()
val distributionFile = "gradle-$wrapperGradleVersion-bin.zip"

tasks.wrapper {
    gradleVersion = wrapperGradleVersion
    distributionUrl = "${repos.getValue("DHE_GRADLE_DISTRIBUTIONS_URL").trimEnd('/')}/$distributionFile"
}

// The wrapper reads its properties file before any build logic runs, so the Gradle version is
// written there as well as in gradle/libs.versions.toml. This keeps the two from drifting.
val verifyWrapperVersion = tasks.register("verifyWrapperVersion") {
    group = "verification"
    description = "Fails if the Gradle wrapper is not on the version in gradle/libs.versions.toml."
    val properties = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")
    val expected = distributionFile
    inputs.file(properties)
    inputs.property("expected", expected)
    doLast {
        val url = java.util.Properties()
            .apply { properties.asFile.inputStream().use { load(it) } }
            .getProperty("distributionUrl")
        if (url == null || !url.endsWith("/$expected")) {
            throw GradleException(
                "gradle/wrapper/gradle-wrapper.properties downloads $url but gradle/libs.versions.toml asks for " +
                    "$expected. Run ./gradlew wrapper."
            )
        }
    }
}

tasks.check {
    dependsOn(verifyWrapperVersion)
    // The convention plugins have tests of their own (fixture builds run with TestKit).
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(gradle.includedBuild("repositories-plugin").task(":check"))
}
