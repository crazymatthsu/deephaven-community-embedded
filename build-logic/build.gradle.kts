plugins {
    `kotlin-dsl`
}

group = "io.github.cmh.dhe.buildlogic"

dependencies {
    // dhe.embedded-spring-app applies the Spring Boot plugin, so it has to be on the classpath.
    // io.spring.dependency-management is deliberately not here (F9).
    implementation(libs.spring.boot.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The tests run the fixture builds under src/test/fixtures with Gradle TestKit. Each fixture
// includes this build the way the root build does, so the plugins are tested as they are used.
tasks.test {
    useJUnitPlatform()
    val fixtures = layout.projectDirectory.dir("src/test/fixtures")
    val workDir = layout.buildDirectory.dir("fixture-builds")
    inputs.dir(fixtures).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("src/main"), fileTree("repositories-plugin/src"), "../gradle/libs.versions.toml", fileTree("../config/repos"))
        .withPropertyName("pluginsUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("dhe.fixtures.dir", fixtures.asFile.absolutePath)
    systemProperty("dhe.fixtures.workDir", workDir.get().asFile.absolutePath)
    systemProperty("dhe.repoRoot", layout.projectDirectory.dir("..").asFile.canonicalPath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
