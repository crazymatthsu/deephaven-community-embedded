// Fixture build for the build-logic tests (build-logic/src/test/kotlin). It includes build-logic
// the way the root build does. The tests pass -PdhcRepoRoot=<repository root>.

pluginManagement {
    val dhcRepoRoot: String by settings
    includeBuild("$dhcRepoRoot/build-logic/repositories-plugin")
    includeBuild("$dhcRepoRoot/build-logic")
}

plugins {
    id("dhc.repositories")
}

val dhcRepoRoot: String by settings

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("$dhcRepoRoot/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "fixture-own-repository"
include("rogue")
