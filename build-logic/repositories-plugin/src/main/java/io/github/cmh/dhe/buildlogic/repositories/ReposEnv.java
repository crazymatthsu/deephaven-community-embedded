package io.github.cmh.dhe.buildlogic.repositories;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

/**
 * The artifact sources of the build: {@code config/repos/repos.env}, overridden key by key by an
 * optional, git-ignored {@code repos.local.env} next to it.
 */
final class ReposEnv {

    static final String MAVEN_REPO_URL = "DHE_MAVEN_REPO_URL";
    static final String GRADLE_PLUGIN_REPO_URL = "DHE_GRADLE_PLUGIN_REPO_URL";
    static final String GRADLE_DISTRIBUTIONS_URL = "DHE_GRADLE_DISTRIBUTIONS_URL";
    static final String REPO_CREDENTIALS = "DHE_REPO_CREDENTIALS";
    static final String CONTAINER_REGISTRY = "DHE_CONTAINER_REGISTRY";

    static final String CREDENTIALS_NONE = "none";
    static final String CREDENTIALS_GRADLE_PROPERTIES = "gradle-properties";

    private static final String RELATIVE_PATH = "config/repos/repos.env";
    private static final String LOCAL_OVERRIDE = "repos.local.env";

    private static final Set<String> URL_KEYS =
            Set.of(MAVEN_REPO_URL, GRADLE_PLUGIN_REPO_URL, GRADLE_DISTRIBUTIONS_URL);
    private static final Set<String> KNOWN_KEYS = Set.of(
            MAVEN_REPO_URL, GRADLE_PLUGIN_REPO_URL, GRADLE_DISTRIBUTIONS_URL, REPO_CREDENTIALS, CONTAINER_REGISTRY);
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final File file;
    private final Map<String, String> values;

    private ReposEnv(File file, Map<String, String> values) {
        this.file = file;
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * Finds {@code config/repos/repos.env} in {@code startDir} or the nearest directory above it.
     * Searching upwards is what lets build-logic and the test fixture builds, which live below the
     * repository root, share the one file.
     */
    static ReposEnv locateAndLoad(File startDir) {
        for (File dir = startDir.getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, RELATIVE_PATH);
            if (candidate.isFile()) {
                return load(candidate);
            }
        }
        throw new GradleException("dhe.repositories: no " + RELATIVE_PATH + " in " + startDir
                + " or any directory above it. Every repository of this build is declared there.");
    }

    private static ReposEnv load(File file) {
        Map<String, String> values = new LinkedHashMap<>(parse(file));
        File local = new File(file.getParentFile(), LOCAL_OVERRIDE);
        if (local.isFile()) {
            values.putAll(parse(local));
        }
        for (String key : KNOWN_KEYS) {
            if (!values.containsKey(key)) {
                throw new GradleException("dhe.repositories: key " + key + " is missing from " + file);
            }
        }
        return new ReposEnv(file, values);
    }

    private static Map<String, String> parse(File file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("dhe.repositories: cannot read " + file, e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = file + ":" + (i + 1);
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new GradleException("dhe.repositories: " + where + " is not KEY=value");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (!KEY.matcher(key).matches()) {
                throw new GradleException("dhe.repositories: " + where + " has an invalid key '" + key + "'");
            }
            if (!KNOWN_KEYS.contains(key)) {
                throw new GradleException(
                        "dhe.repositories: " + where + " has the unknown key " + key + ". Known keys: " + sorted(KNOWN_KEYS));
            }
            if (value.isEmpty() || !value.equals(value.trim()) || value.startsWith("\"") || value.startsWith("'")) {
                throw new GradleException("dhe.repositories: " + where + " needs a plain value for " + key
                        + ": not empty, no quotes, no spaces around '='");
            }
            if (URL_KEYS.contains(key)) {
                checkUrl(where, key, value);
            }
            values.put(key, value);
        }
        return values;
    }

    private static void checkUrl(String where, String key, String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new GradleException("dhe.repositories: " + where + " has an invalid URL for " + key, e);
        }
        if (uri.getScheme() == null) {
            throw new GradleException("dhe.repositories: " + where + " needs an absolute URL for " + key);
        }
        if (uri.getRawUserInfo() != null) {
            throw new GradleException("dhe.repositories: " + where + " has credentials inside the URL for " + key
                    + ". Remove them and set " + REPO_CREDENTIALS + "=" + CREDENTIALS_GRADLE_PROPERTIES + " instead.");
        }
    }

    private static List<String> sorted(Set<String> keys) {
        return keys.stream().sorted().toList();
    }

    File file() {
        return file;
    }

    String get(String key) {
        return values.get(key);
    }

    /** True when the repositories need a login that Gradle reads from Gradle properties. */
    boolean usesCredentials() {
        String mode = get(REPO_CREDENTIALS);
        switch (mode) {
            case CREDENTIALS_NONE:
                return false;
            case CREDENTIALS_GRADLE_PROPERTIES:
                return true;
            default:
                throw new GradleException("dhe.repositories: " + REPO_CREDENTIALS + "=" + mode + " in " + file
                        + " (or its local override) must be " + CREDENTIALS_NONE + " or "
                        + CREDENTIALS_GRADLE_PROPERTIES);
        }
    }

    Map<String, String> asMap() {
        return values;
    }
}
