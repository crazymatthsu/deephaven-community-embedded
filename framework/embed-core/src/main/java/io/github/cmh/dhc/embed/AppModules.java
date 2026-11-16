package io.github.cmh.dhc.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Puts application-mode modules where Deephaven looks for them.
 *
 * <p>Application Mode reads a directory. A module travels in a jar instead, so that depending on
 * the jar is all a host has to do. In the jar a module {@code hello} is
 *
 * <pre>
 * META-INF/dhc/app.d/hello.index      the files of the module, one per line
 * META-INF/dhc/app.d/hello.app        Deephaven's application file
 * META-INF/dhc/app.d/hello/...        the scripts it names
 * </pre>
 *
 * which the {@code dhc.groovy-app-module} plugin writes from {@code src/main/appmode/}. At start
 * the modules an instance runs are copied into its work directory.
 */
final class AppModules {

    /** Where modules are in a jar. */
    static final String RESOURCE_ROOT = "META-INF/dhc/app.d/";

    /** Name of the application directory inside an instance's work directory. */
    static final String DIRECTORY = "app.d";

    private AppModules() {}

    /**
     * The directory Deephaven should run applications from: {@code DHC_APP_DIR} as it is when
     * set, otherwise a fresh copy of the configured modules.
     */
    static Path prepare(EmbedSettings settings, ClassLoader classLoader) {
        if (settings.appDir().isPresent()) {
            Path appDir = settings.appDir().get().toAbsolutePath();
            if (!Files.isDirectory(appDir)) {
                throw new EmbeddedDeephavenException(EmbedSettings.APP_DIR + " is " + appDir
                        + ", which is not a directory. It has to hold the .app files to run.");
            }
            return appDir;
        }
        Path target = settings.workDir().toAbsolutePath().resolve(DIRECTORY);
        try {
            deleteRecursively(target);
            Files.createDirectories(target);
            for (String module : settings.appModules()) {
                materialise(module, classLoader, target);
            }
        } catch (IOException e) {
            throw new EmbeddedDeephavenException("Cannot write the application modules to " + target, e);
        }
        return target;
    }

    /**
     * The longest path an application script may have, as Deephaven is given it (finding F17).
     *
     * <p>Deephaven names the class it compiles a script into after the script's whole path, every
     * character that is not a letter or a digit turned into an underscore, and writes that class
     * to a file of the same name. A file name holds 255 characters on every file system this runs
     * on. The rest of the name needs room too: a counter that grows each time the scripts run
     * again, the names of the closures in the script, and {@code .class}. 40 characters are kept
     * for those.
     */
    static final int MAX_SCRIPT_PATH = 215;

    /**
     * The way to write a directory that gives Deephaven the shortest script paths: relative to
     * the directory the JVM was started in when that is shorter, as it is when an application is
     * started from its checkout or its installation. A deep directory then costs nothing.
     *
     * @param directory absolute
     * @param workingDirectory the JVM's, absolute
     */
    static Path shortest(Path directory, Path workingDirectory) {
        Path relative;
        try {
            relative = workingDirectory.relativize(directory);
        } catch (IllegalArgumentException anotherDrive) {
            return directory;
        }
        if (relative.toString().isEmpty()) {
            relative = Path.of(".");
        }
        return relative.toString().length() < directory.toString().length() ? relative : directory;
    }

    /**
     * The script files of the enabled script applications in a directory, in the order Deephaven
     * runs them, each as Deephaven is given it: the directory with the file's name from the
     * {@code .app} file.
     */
    static List<Path> scripts(Path applicationDir) {
        List<Path> applications;
        try (Stream<Path> files = Files.list(applicationDir)) {
            applications = files.filter(file -> file.getFileName().toString().endsWith(".app")).sorted().toList();
        } catch (IOException e) {
            throw new EmbeddedDeephavenException("Cannot list the application modules in " + applicationDir, e);
        }
        List<Path> scripts = new ArrayList<>();
        for (Path application : applications) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(application)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new EmbeddedDeephavenException("Cannot read " + application, e);
            }
            if (!"script".equals(properties.getProperty("type")) || "false".equals(properties.getProperty("enabled"))) {
                continue;
            }
            for (int i = 0; properties.getProperty("file_" + i) != null; i++) {
                scripts.add(applicationDir.resolve(properties.getProperty("file_" + i).trim()));
            }
        }
        return scripts;
    }

    /**
     * Refuses a directory whose scripts have paths too long for Deephaven to compile them
     * (finding F17), before Deephaven fails on the first of them with "File name too long".
     */
    static void checkScriptPaths(Path applicationDir) {
        for (Path script : scripts(applicationDir)) {
            int length = script.toString().length();
            if (length > MAX_SCRIPT_PATH) {
                throw new EmbeddedDeephavenException("The application script " + script + " has a path of " + length
                        + " characters, and " + MAX_SCRIPT_PATH + " is the longest Deephaven can run. Deephaven names"
                        + " the class file of a script after the script's whole path, and a file name holds 255"
                        + " characters; without this check the start would fail with 'File name too long'. Point "
                        + EmbedSettings.WORK_DIR + " at a directory with a shorter path, or start the application"
                        + " from a directory nearer to it: a path is taken relative to the directory the JVM was"
                        + " started in (" + Path.of("").toAbsolutePath() + ") when that is shorter.");
            }
        }
    }

    private static void materialise(String module, ClassLoader classLoader, Path target) throws IOException {
        String indexName = RESOURCE_ROOT + module + ".index";
        List<String> files;
        try (InputStream index = classLoader.getResourceAsStream(indexName)) {
            if (index == null) {
                throw new EmbeddedDeephavenException("Application module '" + module + "' is not on the classpath"
                        + " (no " + indexName + "). Add the module's project as a dependency of the host, or"
                        + " correct " + EmbedSettings.APP_MODULES + ".");
            }
            files = lines(new String(index.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (String file : files) {
            Path destination = target.resolve(file).normalize();
            if (!destination.startsWith(target)) {
                throw new EmbeddedDeephavenException("Application module '" + module + "' names the file '" + file
                        + "', which is outside the module.");
            }
            try (InputStream content = classLoader.getResourceAsStream(RESOURCE_ROOT + file)) {
                if (content == null) {
                    throw new EmbeddedDeephavenException("Application module '" + module + "' lists '" + file
                            + "' but the jar does not contain " + RESOURCE_ROOT + file + ".");
                }
                Files.createDirectories(destination.getParent());
                Files.copy(content, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
