package io.github.cmh.dhc.embed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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
