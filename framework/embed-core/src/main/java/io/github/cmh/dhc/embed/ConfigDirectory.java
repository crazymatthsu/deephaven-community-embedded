package io.github.cmh.dhc.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The configuration directory of design section 6: settings as files, one set for every
 * environment the same build runs in.
 *
 * <pre>
 * defaults.env          every key with its default
 * apps/&lt;app id&gt;.env     what makes an application that application
 * env/&lt;environment&gt;.env  what differs between dev, uat and prod
 * </pre>
 *
 * <p>Later files override earlier ones. The files are plain {@code KEY=value} lines, the format
 * the shell scripts and a compose {@code env_file} read too. In an environment file a key can be
 * scoped to one application by putting the application's id after the prefix:
 * {@code DHC_DHC_B_HTTP_PORT} is {@code DHC_HTTP_PORT} for the application {@code dhc-b}, and
 * wins over the unscoped key in the same file.
 *
 * <p>{@code DHC_CONFIG_DIR} says where the directory is and {@code DHC_ENV} which environment
 * applies. Neither can be set inside the directory.
 */
public final class ConfigDirectory {

    /** Where the directory is. Without it there is no file layer at all. */
    public static final String CONFIG_DIR = "DHC_CONFIG_DIR";

    /** Which {@code env/<name>.env} applies. Without it only the defaults and the application's file do. */
    public static final String ENV = "DHC_ENV";

    private static final String PREFIX = "DHC_";

    private ConfigDirectory() {}

    /**
     * A key scoped to one application: {@code DHC_HTTP_PORT} for {@code dhc-b} is
     * {@code DHC_DHC_B_HTTP_PORT}.
     */
    public static String scopedKey(String appId, String key) {
        if (!key.startsWith(PREFIX)) {
            throw new EmbeddedDeephavenException("Setting " + key + " is not a setting name. Settings are named DHC_<NAME>.");
        }
        return PREFIX + appId.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + key.substring(PREFIX.length());
    }

    /**
     * The settings the directory holds for one application in one environment, with the keys
     * scoped to that application already resolved to plain keys. Keys scoped to other
     * applications are left as they are; nobody asks for them.
     *
     * @param environment the name after {@code env/}, or empty for none
     * @throws EmbeddedDeephavenException when the directory, a file or a line is not what it should be
     */
    public static Map<String, String> load(Path directory, String appId, Optional<String> environment) {
        Path root = directory.toAbsolutePath();
        Path defaults = root.resolve("defaults.env");
        if (!Files.isRegularFile(defaults)) {
            throw new EmbeddedDeephavenException(CONFIG_DIR + " is " + root + ", which has no defaults.env. It has to be"
                    + " the configuration directory: defaults.env, apps/ and env/.");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        apply(settings, parse(defaults), appId);
        Path application = root.resolve("apps").resolve(appId + ".env");
        if (Files.isRegularFile(application)) {
            apply(settings, parse(application), appId);
        }
        if (environment.isPresent()) {
            Path file = root.resolve("env").resolve(environment.get() + ".env");
            if (!Files.isRegularFile(file)) {
                throw new EmbeddedDeephavenException(ENV + " is '" + environment.get() + "', but there is no " + file
                        + ". Environments in " + root.resolve("env") + ": " + environments(root));
            }
            apply(settings, parse(file), appId);
        }
        return settings;
    }

    /** One file over what earlier files said: its plain keys, then its keys scoped to this application. */
    private static void apply(Map<String, String> settings, Map<String, String> file, String appId) {
        String scope = scopedKey(appId, PREFIX);
        Map<String, String> scoped = new LinkedHashMap<>();
        file.forEach((key, value) -> {
            if (key.startsWith(scope)) {
                scoped.put(PREFIX + key.substring(scope.length()), value);
            } else {
                settings.put(key, value);
            }
        });
        settings.putAll(scoped);
    }

    private static List<String> environments(Path root) {
        try (Stream<Path> files = Files.list(root.resolve("env"))) {
            return List.copyOf(new TreeSet<>(files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".env"))
                    .map(name -> name.substring(0, name.length() - ".env".length()))
                    .toList()));
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The keys and values of one file, in the order written. */
    static Map<String, String> parse(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EmbeddedDeephavenException("Cannot read " + file, e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            String key = equals < 0 ? "" : line.substring(0, equals);
            if (!key.matches("DHC_[A-Z0-9_]+")) {
                throw new EmbeddedDeephavenException(file + " line " + (i + 1) + " is not KEY=value with a key named"
                        + " DHC_<NAME>: " + line);
            }
            values.put(key, line.substring(equals + 1).strip());
        }
        return values;
    }
}
