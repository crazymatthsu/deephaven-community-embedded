package io.github.cmh.dhc.buildlogic.repositories;

import org.gradle.api.Plugin;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.resolve.DependencyResolutionManagement;
import org.gradle.api.initialization.resolve.RepositoriesMode;
import org.gradle.api.plugins.ExtensionAware;

/**
 * Settings plugin {@code dhc.repositories}: the only place this build declares repositories.
 *
 * <p>Every URL comes from {@code config/repos/repos.env}. A project that declares a repository of
 * its own fails the build ({@link RepositoriesMode#FAIL_ON_PROJECT_REPOS}), so a module cannot
 * quietly add a source the company mirror does not know about.
 *
 * <p>The resolved values are also published as the extra property {@value #EXTRA_PROPERTY} on the
 * {@code gradle} object (a {@code Map<String, String>}) for the few build scripts that need one,
 * such as the wrapper task.
 */
public final class DhcRepositoriesPlugin implements Plugin<Settings> {

    /** Name of the extra property on {@code gradle} that holds the values of repos.env. */
    public static final String EXTRA_PROPERTY = "dhc.repos";

    private static final String MAVEN_REPO_NAME = "dhcMaven";
    private static final String PLUGIN_REPO_NAME = "dhcPlugins";

    @Override
    public void apply(Settings settings) {
        ReposEnv repos = ReposEnv.locateAndLoad(settings.getRootDir());
        boolean credentials = repos.usesCredentials();
        String mavenUrl = repos.get(ReposEnv.MAVEN_REPO_URL);
        String pluginUrl = repos.get(ReposEnv.GRADLE_PLUGIN_REPO_URL);

        // Where plugins applied with plugins { id(...) } are looked up.
        RepositoryHandler pluginRepositories = settings.getPluginManagement().getRepositories();
        declare(pluginRepositories, PLUGIN_REPO_NAME, pluginUrl, credentials);
        declare(pluginRepositories, MAVEN_REPO_NAME, mavenUrl, credentials);

        // Where every project's dependencies are looked up.
        DependencyResolutionManagement dependencies = settings.getDependencyResolutionManagement();
        dependencies.getRepositoriesMode().set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);
        declare(dependencies.getRepositories(), MAVEN_REPO_NAME, mavenUrl, credentials);

        ((ExtensionAware) settings.getGradle()).getExtensions().getExtraProperties().set(EXTRA_PROPERTY, repos.asMap());
    }

    private static void declare(RepositoryHandler repositories, String name, String url, boolean credentials) {
        repositories.maven(repository -> {
            repository.setName(name);
            repository.setUrl(url);
            if (credentials) {
                // Gradle reads <name>Username and <name>Password from Gradle properties.
                repository.credentials(PasswordCredentials.class);
            }
        });
    }
}
