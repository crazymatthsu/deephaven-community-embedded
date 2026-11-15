pluginManagement {
    includeBuild("repositories-plugin")
}

plugins {
    // build-logic gets its repositories the same way the root build does.
    id("dhe.repositories")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
