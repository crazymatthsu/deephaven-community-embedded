// Fixture build for the build-logic tests (build-logic/src/test/kotlin). It includes build-logic
// the way the root build does. The tests pass -PdheRepoRoot=<repository root>.

pluginManagement {
    val dheRepoRoot: String by settings
    includeBuild("$dheRepoRoot/build-logic/repositories-plugin")
    includeBuild("$dheRepoRoot/build-logic")
}

plugins {
    id("dhe.repositories")
}

val dheRepoRoot: String by settings

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("$dheRepoRoot/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "fixture-own-repository"
include("rogue")
