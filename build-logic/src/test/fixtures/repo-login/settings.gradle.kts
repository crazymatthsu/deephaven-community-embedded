// Fixture build: applies dhc.repositories against its own config/repos/repos.env, which names a
// mirror that needs a login. It resolves nothing, so no address in that file is ever contacted.
// The tests pass -PdhcRepoRoot=<repository root>.

pluginManagement {
    val dhcRepoRoot: String by settings
    includeBuild("$dhcRepoRoot/build-logic/repositories-plugin")
}

plugins {
    id("dhc.repositories")
}

rootProject.name = "repo-login"

// What the plugin declared. The password itself is never printed.
fun describe(scope: String, repositories: RepositoryHandler) {
    repositories.withType<MavenArtifactRepository>().forEach {
        println("$scope ${it.name} ${it.url} user=${it.credentials.username} password=${if (it.credentials.password != null) "set" else "missing"}")
    }
}
describe("plugins", pluginManagement.repositories)
describe("dependencies", dependencyResolutionManagement.repositories)
