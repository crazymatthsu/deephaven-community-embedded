package io.github.cmh.dhc.embed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * How one embedded Deephaven instance is set up: where it listens, how clients log in, which
 * application-mode modules it runs and where it keeps its files.
 *
 * <p>Every setting has one key with two spellings: {@code DHC_HTTP_PORT} as an environment
 * variable and {@code dhc.http.port} as a system property. Lowest precedence first: the built-in
 * default, the application's own defaults, the environment, {@code -D}. The configuration
 * directory of design section 6 arrives in phase P6 and slots in between.
 */
public final class EmbedSettings {

    /** How clients of the web UI and of the API prove who they are. */
    public enum AuthMode {
        /** Deephaven's pre-shared key. The default. */
        PSK,
        /** Nobody logs in. For development only. */
        ANONYMOUS
    }

    public static final String HTTP_HOST = "DHC_HTTP_HOST";
    public static final String HTTP_PORT = "DHC_HTTP_PORT";
    public static final String AUTH_MODE = "DHC_AUTH_MODE";
    public static final String AUTH_PSK = "DHC_AUTH_PSK";
    public static final String APP_MODULES = "DHC_APP_MODULES";
    public static final String APP_DIR = "DHC_APP_DIR";
    public static final String WORK_DIR = "DHC_WORK_DIR";

    private static final List<String> KEYS =
            List.of(HTTP_HOST, HTTP_PORT, AUTH_MODE, AUTH_PSK, APP_MODULES, APP_DIR, WORK_DIR);

    private final String appId;
    private final String httpHost;
    private final int httpPort;
    private final AuthMode authMode;
    private final String preSharedKey;
    private final List<String> appModules;
    private final Path appDir;
    private final Path workDir;

    private EmbedSettings(Builder builder) {
        this.appId = builder.appId;
        this.httpHost = builder.httpHost;
        this.httpPort = builder.httpPort;
        this.authMode = builder.authMode;
        this.preSharedKey = builder.preSharedKey;
        this.appModules = List.copyOf(builder.appModules);
        this.appDir = builder.appDir;
        this.workDir = builder.workDir != null
                ? builder.workDir
                : Path.of(System.getProperty("java.io.tmpdir"), "dhc-embedded", builder.appId);
    }

    /** Settings for an application from the environment and {@code -D}, over the built-in defaults. */
    public static EmbedSettings load(String appId) {
        return load(appId, Map.of());
    }

    /**
     * Settings for an application from the environment and {@code -D}, over the application's own
     * defaults, over the built-in ones.
     *
     * @param appDefaults what makes this application this application, keyed like the environment
     *        ({@code DHC_APP_MODULES}, {@code DHC_HTTP_PORT}, ...)
     */
    public static EmbedSettings load(String appId, Map<String, String> appDefaults) {
        return load(appId, appDefaults, System.getenv(), System::getProperty);
    }

    /** As {@link #load(String, Map)}, with the environment and system properties passed in. */
    public static EmbedSettings load(String appId, Map<String, String> appDefaults, Map<String, String> environment,
            Function<String, String> systemProperties) {
        for (String key : appDefaults.keySet()) {
            if (!KEYS.contains(key)) {
                throw new EmbeddedDeephavenException("Unknown setting " + key + " in the defaults of application "
                        + appId + ". Known settings: " + KEYS);
            }
        }
        Function<String, Optional<String>> value = key -> {
            String property = systemProperties.apply(systemPropertyName(key));
            if (property != null && !property.isBlank()) {
                return Optional.of(property.trim());
            }
            String variable = environment.get(key);
            if (variable != null && !variable.isBlank()) {
                return Optional.of(variable.trim());
            }
            return Optional.ofNullable(appDefaults.get(key)).filter(text -> !text.isBlank()).map(String::trim);
        };

        Builder builder = builder(appId);
        value.apply(HTTP_HOST).ifPresent(builder::httpHost);
        value.apply(HTTP_PORT).ifPresent(text -> builder.httpPort(parsePort(text)));
        value.apply(AUTH_MODE).ifPresent(text -> builder.authMode(parseAuthMode(text)));
        value.apply(AUTH_PSK).ifPresent(builder::preSharedKey);
        value.apply(APP_MODULES).ifPresent(text -> builder.appModules(parseList(text)));
        value.apply(APP_DIR).ifPresent(text -> builder.appDir(Path.of(text)));
        value.apply(WORK_DIR).ifPresent(text -> builder.workDir(Path.of(text)));
        return builder.build();
    }

    /** {@code DHC_HTTP_PORT} to {@code dhc.http.port}. */
    static String systemPropertyName(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 0 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new EmbeddedDeephavenException(HTTP_PORT + " must be a port number from 0 to 65535, but it is '"
                    + text + "'. 0 picks a free port.");
        }
    }

    private static AuthMode parseAuthMode(String text) {
        try {
            return AuthMode.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EmbeddedDeephavenException(AUTH_MODE + " must be psk or anonymous, but it is '" + text + "'.");
        }
    }

    private static List<String> parseList(String text) {
        List<String> items = new ArrayList<>();
        for (String item : text.split(",")) {
            if (!item.isBlank()) {
                items.add(item.trim());
            }
        }
        return items;
    }

    public static Builder builder(String appId) {
        return new Builder(appId);
    }

    public Builder toBuilder() {
        Builder builder = new Builder(appId);
        builder.httpHost = httpHost;
        builder.httpPort = httpPort;
        builder.authMode = authMode;
        builder.preSharedKey = preSharedKey;
        builder.appModules = appModules;
        builder.appDir = appDir;
        builder.workDir = workDir;
        return builder;
    }

    /** Short name of the application, for example {@code dhc-a}. Names its directories and logs. */
    public String appId() {
        return appId;
    }

    /** Interface the Deephaven web UI and API listen on. {@code localhost} unless set. */
    public String httpHost() {
        return httpHost;
    }

    /** Port of the Deephaven web UI and API. 0 picks a free one; see {@link EmbeddedDeephaven#httpPort()}. */
    public int httpPort() {
        return httpPort;
    }

    public AuthMode authMode() {
        return authMode;
    }

    /** The pre-shared key, when the host supplies one. Without it Deephaven makes one up and logs it. */
    public Optional<String> preSharedKey() {
        return Optional.ofNullable(preSharedKey);
    }

    /** Ids of the application-mode modules to run, taken from the classpath. */
    public List<String> appModules() {
        return appModules;
    }

    /** A directory of application-mode modules to run as it is, instead of the classpath ones. */
    public Optional<Path> appDir() {
        return Optional.ofNullable(appDir);
    }

    /** This instance's own directory: compiled formulas, materialised modules, Deephaven's data (F10). */
    public Path workDir() {
        return workDir;
    }

    /** Never shows the pre-shared key. */
    @Override
    public String toString() {
        return "EmbedSettings{appId=" + appId + ", httpHost=" + httpHost + ", httpPort=" + httpPort + ", authMode="
                + authMode + ", preSharedKey=" + (preSharedKey == null ? "generated" : "supplied") + ", appModules="
                + appModules + ", appDir=" + appDir + ", workDir=" + workDir + "}";
    }

    public static final class Builder {
        private final String appId;
        private String httpHost = "localhost";
        private int httpPort = 10000;
        private AuthMode authMode = AuthMode.PSK;
        private String preSharedKey;
        private List<String> appModules = List.of();
        private Path appDir;
        private Path workDir;

        private Builder(String appId) {
            if (appId == null || !appId.matches("[a-z][a-z0-9-]*")) {
                throw new EmbeddedDeephavenException("An application id is lower-case letters, digits and '-', "
                        + "starting with a letter, for example dhc-a. Got: " + appId);
            }
            this.appId = appId;
        }

        public Builder httpHost(String httpHost) {
            this.httpHost = Objects.requireNonNull(httpHost, "httpHost");
            return this;
        }

        public Builder httpPort(int httpPort) {
            this.httpPort = httpPort;
            return this;
        }

        public Builder authMode(AuthMode authMode) {
            this.authMode = Objects.requireNonNull(authMode, "authMode");
            return this;
        }

        public Builder preSharedKey(String preSharedKey) {
            this.preSharedKey = preSharedKey;
            return this;
        }

        public Builder appModules(List<String> appModules) {
            this.appModules = List.copyOf(appModules);
            return this;
        }

        public Builder appModules(String... appModules) {
            return appModules(List.of(appModules));
        }

        public Builder appDir(Path appDir) {
            this.appDir = appDir;
            return this;
        }

        public Builder workDir(Path workDir) {
            this.workDir = workDir;
            return this;
        }

        public EmbedSettings build() {
            return new EmbedSettings(this);
        }
    }
}
