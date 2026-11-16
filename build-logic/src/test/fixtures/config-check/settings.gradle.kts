// Fixture build for the validateConfig tests. A small, correct configuration directory; the
// tests break it one way at a time. The tests pass -PdhcRepoRoot=<repository root>.

pluginManagement {
    val dhcRepoRoot: String by settings
    includeBuild("$dhcRepoRoot/build-logic/repositories-plugin")
    includeBuild("$dhcRepoRoot/build-logic")
}

plugins {
    id("dhc.repositories")
}

rootProject.name = "fixture-config-check"
include("config")
