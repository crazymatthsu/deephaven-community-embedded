// Aggregate tasks only. Everything a module needs comes from a convention plugin in build-logic/.

plugins {
    base
}

@Suppress("UNCHECKED_CAST")
val repos = gradle.extra["dhc.repos"] as Map<String, String> // config/repos/repos.env, set by dhc.repositories
val wrapperGradleVersion = libs.versions.gradle.get()
val wrapperDistributionUrl =
    "${repos.getValue("DHC_GRADLE_DISTRIBUTIONS_URL").trimEnd('/')}/gradle-$wrapperGradleVersion-bin.zip"

tasks.wrapper {
    gradleVersion = wrapperGradleVersion
    distributionUrl = wrapperDistributionUrl
}

// The wrapper reads its properties file before any build logic runs, so the address it downloads
// Gradle from is written there as well as in config/repos/repos.env (where) and
// gradle/libs.versions.toml (which version). This keeps the copy from drifting.
val verifyWrapper = tasks.register("verifyWrapper") {
    group = "verification"
    description = "Fails if the Gradle wrapper does not download the Gradle version of gradle/libs.versions.toml " +
        "from the address in config/repos/repos.env."
    val properties = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")
    val expected = wrapperDistributionUrl
    inputs.file(properties)
    inputs.property("expected", expected)
    doLast {
        val url = java.util.Properties()
            .apply { properties.asFile.inputStream().use { load(it) } }
            .getProperty("distributionUrl")
        if (url != expected) {
            throw GradleException(
                "gradle/wrapper/gradle-wrapper.properties downloads Gradle from\n  $url\n" +
                    "but config/repos/repos.env and gradle/libs.versions.toml ask for\n  $expected\n" +
                    "Run scripts/sync-gradle-wrapper.sh (it needs no Gradle), or ./gradlew wrapper."
            )
        }
    }
}

tasks.check {
    dependsOn(verifyWrapper)
    // The convention plugins have tests of their own (fixture builds run with TestKit).
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(gradle.includedBuild("repositories-plugin").task(":check"))
}
