// This build declares no repositories on purpose. It is plain Java against the Gradle API, so it
// needs nothing from the network, and that is what lets every other build (the root build and
// build-logic itself) take its repositories from the plugin built here.
rootProject.name = "repositories-plugin"
