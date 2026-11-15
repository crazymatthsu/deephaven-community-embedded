pluginManagement {
    // The repositories plugin is its own tiny build so that build-logic can apply it too.
    includeBuild("build-logic/repositories-plugin")
    includeBuild("build-logic")
}

plugins {
    // The only place repositories come from. Reads config/repos/repos.env.
    id("dhc.repositories")
}

rootProject.name = "deephaven-community-embedded"

// Shared Java code.
include("framework:embed-core")
include("framework:embed-spring")
include("framework:embed-testkit")
include("framework:orders-model")

// Embedded Deephaven applications: role modules, then the hosts that run them.
include("apps:dhc-a-module")
include("apps:dhc-b-module")
include("apps:dhc-a-java")
include("apps:dhc-b-spring")
include("apps:dhc-a-spring")
include("apps:dhc-b-java")

// Non-Java directories that own tasks and take part in `check`.
include("config")
include("scripts")
include("docker")
include("docs")
