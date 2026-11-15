package io.github.cmh.dhc.buildlogic.repositories;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.resolve.DependencyResolutionManagement;
import org.gradle.api.initialization.resolve.RepositoriesMode;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ProviderFactory;

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

    /** Gradle properties that hold the one login used for every repository. */
    public static final String USERNAME_PROPERTY = "dhcRepoUsername";
    public static final String PASSWORD_PROPERTY = "dhcRepoPassword";

    private static final String MAVEN_REPO_NAME = "dhcMaven";
    private static final String PLUGIN_REPO_NAME = "dhcPlugins";

    @Override
    public void apply(Settings settings) {
        ReposEnv repos = ReposEnv.locateAndLoad(settings.getRootDir());
        Login login = repos.usesCredentials() ? Login.read(settings.getProviders(), repos) : null;
        String mavenUrl = repos.get(ReposEnv.MAVEN_REPO_URL);
        String pluginUrl = repos.get(ReposEnv.GRADLE_PLUGIN_REPO_URL);

        // Where plugins applied with plugins { id(...) } are looked up.
        RepositoryHandler pluginRepositories = settings.getPluginManagement().getRepositories();
        declare(pluginRepositories, PLUGIN_REPO_NAME, pluginUrl, login);
        declare(pluginRepositories, MAVEN_REPO_NAME, mavenUrl, login);

        // Where every project's dependencies are looked up.
        DependencyResolutionManagement dependencies = settings.getDependencyResolutionManagement();
        dependencies.getRepositoriesMode().set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);
        declare(dependencies.getRepositories(), MAVEN_REPO_NAME, mavenUrl, login);

        ((ExtensionAware) settings.getGradle()).getExtensions().getExtraProperties().set(EXTRA_PROPERTY, repos.asMap());
    }

    private static void declare(RepositoryHandler repositories, String name, String url, Login login) {
        repositories.maven(repository -> {
            repository.setName(name);
            repository.setUrl(url);
            if (login != null) {
                repository.credentials(credentials -> {
                    credentials.setUsername(login.username);
                    credentials.setPassword(login.password);
                });
            }
        });
    }

    /**
     * The one login for every repository. A company mirror is one server with one account, so the
     * build asks for one user name and one password, not a pair per repository.
     */
    private static final class Login {
        final String username;
        final String password;

        private Login(String username, String password) {
            this.username = username;
            this.password = password;
        }

        static Login read(ProviderFactory providers, ReposEnv repos) {
            return new Login(required(providers, USERNAME_PROPERTY, repos), required(providers, PASSWORD_PROPERTY, repos));
        }

        private static String required(ProviderFactory providers, String property, ReposEnv repos) {
            String value = providers.gradleProperty(property).getOrNull();
            if (value == null || value.isEmpty()) {
                throw new GradleException("dhc.repositories: " + ReposEnv.REPO_CREDENTIALS + "="
                        + ReposEnv.CREDENTIALS_GRADLE_PROPERTIES + " in " + repos.file() + " (or its local override)"
                        + " but the Gradle property " + property + " is not set. Put " + USERNAME_PROPERTY + " and "
                        + PASSWORD_PROPERTY + " in the gradle.properties of your Gradle user home (~/.gradle), or set the"
                        + " environment variables ORG_GRADLE_PROJECT_" + USERNAME_PROPERTY + " and ORG_GRADLE_PROJECT_"
                        + PASSWORD_PROPERTY + ". They never go in a file of this repository.");
            }
            return value;
        }
    }
}
