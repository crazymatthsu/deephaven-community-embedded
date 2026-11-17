// Fixture build for the checkDocs tests. A few documents whose links all lead somewhere; the
// tests break them one way at a time. The tests pass -PdhcRepoRoot=<repository root>.

pluginManagement {
    val dhcRepoRoot: String by settings
    includeBuild("$dhcRepoRoot/build-logic/repositories-plugin")
    includeBuild("$dhcRepoRoot/build-logic")
}

plugins {
    id("dhc.repositories")
}

rootProject.name = "fixture-docs-check"
include("docs")
