// Fixture build for the checkScripts tests. Three scripts that keep the rules; the tests break
// them one way at a time. The tests pass -PdhcRepoRoot=<repository root>.

pluginManagement {
    val dhcRepoRoot: String by settings
    includeBuild("$dhcRepoRoot/build-logic/repositories-plugin")
    includeBuild("$dhcRepoRoot/build-logic")
}

plugins {
    id("dhc.repositories")
}

rootProject.name = "fixture-scripts-check"
include("scripts")
