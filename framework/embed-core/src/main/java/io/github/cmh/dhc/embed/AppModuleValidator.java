package io.github.cmh.dhc.embed;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Checks a directory of Groovy application-mode modules before it is packaged, so that a typo in
 * an {@code .app} file or a script fails the build and not the start of the application.
 *
 * <p>The layout it expects, for a module {@code hello}:
 *
 * <pre>
 * hello.app            type=script, scriptType=groovy, id=hello, name=..., file_0=hello/...
 * hello/10_hello.groovy
 * </pre>
 *
 * <p>The {@code validateAppModule} task of the {@code dhc.groovy-app-module} plugin runs
 * {@link #main} on the module's own classpath, so scripts are parsed by the Groovy that Deephaven
 * brings, not by Gradle's.
 */
public final class AppModuleValidator {

    private static final Set<String> KEYS = Set.of("type", "scriptType", "enabled", "id", "name");

    private AppModuleValidator() {}

    /** Arguments: the directory to check. Exits with status 1 and one line per problem. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: AppModuleValidator <appmode directory>");
            System.exit(2);
        }
        List<String> problems = validate(Path.of(args[0]));
        if (problems.isEmpty()) {
            return;
        }
        System.err.println(problems.size() + " problem(s) in " + args[0] + ":");
        problems.forEach(problem -> System.err.println("  " + problem));
        System.exit(1);
    }

    /** Every problem found in the directory. Empty when it is fine. */
    public static List<String> validate(Path directory) {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            problems.add(directory + " is not a directory");
            return problems;
        }
        List<Path> appFiles;
        try (Stream<Path> files = Files.list(directory)) {
            appFiles = files.filter(file -> file.getFileName().toString().endsWith(".app")).sorted().toList();
        } catch (IOException e) {
            problems.add("cannot list " + directory + ": " + e.getMessage());
            return problems;
        }
        if (appFiles.isEmpty()) {
            problems.add("no .app file in " + directory);
        }
        for (Path appFile : appFiles) {
            validateApp(directory, appFile, problems);
        }
        return problems;
    }

    private static void validateApp(Path directory, Path appFile, List<String> problems) {
        String fileName = appFile.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".app".length());
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(appFile)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            problems.add(fileName + ": cannot be read as a properties file: " + e.getMessage());
            return;
        }

        expect(properties, "type", "script", fileName, problems);
        expect(properties, "scriptType", "groovy", fileName, problems);
        expect(properties, "id", id, fileName, problems);
        if (!id.matches("[a-z][a-z0-9-]*")) {
            problems.add(fileName + ": a module id is lower-case letters, digits and '-', starting with a letter");
        }
        String name = properties.getProperty("name");
        if (name == null || name.isBlank()) {
            problems.add(fileName + ": name is missing");
        }
        String enabled = properties.getProperty("enabled");
        if (enabled != null && !enabled.equals("true") && !enabled.equals("false")) {
            problems.add(fileName + ": enabled is '" + enabled + "', expected true or false");
        }

        Set<String> unknown = new TreeSet<>();
        int scripts = 0;
        for (String key : properties.stringPropertyNames()) {
            if (key.matches("file_\\d+")) {
                scripts++;
            } else if (!KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            problems.add(fileName + ": unknown key(s) " + unknown + "; known keys are " + new TreeSet<>(KEYS)
                    + " and file_0, file_1, ...");
        }
        if (scripts == 0) {
            problems.add(fileName + ": no script; add file_0=" + id + "/<script>.groovy");
        }
        for (int i = 0; i < scripts; i++) {
            String script = properties.getProperty("file_" + i);
            if (script == null) {
                problems.add(fileName + ": file_" + i + " is missing; the file_N keys must count up from 0"
                        + " without a gap, because Deephaven stops at the first missing one");
                continue;
            }
            validateScript(directory, id, fileName, "file_" + i, script.trim(), problems);
        }
    }

    private static void expect(Properties properties, String key, String expected, String fileName,
            List<String> problems) {
        String actual = properties.getProperty(key);
        if (actual == null) {
            problems.add(fileName + ": " + key + " is missing, expected " + key + "=" + expected);
        } else if (!actual.trim().equals(expected)) {
            problems.add(fileName + ": " + key + " is '" + actual.trim() + "', expected '" + expected + "'");
        }
    }

    private static void validateScript(Path directory, String id, String fileName, String key, String script,
            List<String> problems) {
        if (!script.startsWith(id + "/") || script.contains("..") || script.contains("\\")) {
            problems.add(fileName + ": " + key + "=" + script + " must be a path below " + id
                    + "/, written with '/', so two modules in one application cannot overwrite each other's scripts");
            return;
        }
        if (!script.endsWith(".groovy")) {
            problems.add(fileName + ": " + key + "=" + script + " is not a .groovy file");
            return;
        }
        Path file = directory.resolve(script);
        if (!Files.isRegularFile(file)) {
            problems.add(fileName + ": " + key + "=" + script + " does not exist");
            return;
        }
        String syntaxError = groovySyntaxError(file.toFile());
        if (syntaxError != null) {
            problems.add(script + ": does not parse: " + syntaxError);
        }
    }

    /**
     * Parses a script without running it. Groovy is used by name: this module does not compile
     * against it, the classpath of an application has it because Deephaven brings it.
     */
    private static String groovySyntaxError(File script) {
        try {
            Class<?> unitClass = Class.forName("org.codehaus.groovy.control.CompilationUnit");
            int conversion = Class.forName("org.codehaus.groovy.control.Phases").getField("CONVERSION").getInt(null);
            Object unit = unitClass.getConstructor().newInstance();
            unitClass.getMethod("addSource", File.class).invoke(unit, script);
            unitClass.getMethod("compile", int.class).invoke(unit, conversion);
            return null;
        } catch (InvocationTargetException e) {
            String message = String.valueOf(e.getCause().getMessage());
            return message.strip().replaceAll("\\s*\\R\\s*", " ");
        } catch (ReflectiveOperationException e) {
            return "Groovy is not on the classpath of the validator (" + e + ")";
        }
    }
}
