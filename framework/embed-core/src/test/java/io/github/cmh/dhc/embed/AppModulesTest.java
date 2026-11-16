package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T1: validating a module's source directory, and copying a packaged module out of a classpath. */
class AppModulesTest {

    private static final String APP = """
            type=script
            scriptType=groovy
            enabled=true
            id=demo
            name=Demo
            file_0=demo/10_demo.groovy
            """;

    private static Path module(Path directory, String app, String script) throws IOException {
        Files.createDirectories(directory.resolve("demo"));
        Files.writeString(directory.resolve("demo.app"), app);
        Files.writeString(directory.resolve("demo/10_demo.groovy"), script);
        return directory;
    }

    @Test
    void aWellFormedModuleHasNoProblems(@TempDir Path directory) throws IOException {
        assertEquals(List.of(), AppModuleValidator.validate(module(directory, APP, "demo = emptyTable(1)\n")));
    }

    @Test
    void aScriptThatDoesNotParseIsAProblem(@TempDir Path directory) throws IOException {
        List<String> problems = AppModuleValidator.validate(module(directory, APP, "demo = emptyTable(1\n"));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("demo/10_demo.groovy: does not parse"), problems.toString());
    }

    @Test
    void wrongKeysAndMissingFilesAreEachReported(@TempDir Path directory) throws IOException {
        String app = """
                type=script
                scriptType=python
                id=other
                name=Demo
                file_0=demo/10_demo.groovy
                file_2=demo/missing.groovy
                fiel_1=demo/typo.groovy
                """;

        String problems = String.join("\n", AppModuleValidator.validate(module(directory, app, "demo = 1\n")));

        assertTrue(problems.contains("scriptType is 'python', expected 'groovy'"), problems);
        assertTrue(problems.contains("id is 'other', expected 'demo'"), problems);
        assertTrue(problems.contains("unknown key(s) [fiel_1]"), problems);
        assertTrue(problems.contains("file_1 is missing"), problems);
    }

    @Test
    void aScriptOutsideTheModulesOwnDirectoryIsAProblem(@TempDir Path directory) throws IOException {
        String app = APP.replace("file_0=demo/10_demo.groovy", "file_0=10_demo.groovy");

        String problems = String.join("\n", AppModuleValidator.validate(module(directory, app, "demo = 1\n")));

        assertTrue(problems.contains("must be a path below demo/"), problems);
    }

    @Test
    void aPackagedModuleIsCopiedIntoTheWorkDirectory(@TempDir Path classpathRoot, @TempDir Path workDir)
            throws IOException {
        Path packaged = Files.createDirectories(classpathRoot.resolve(AppModules.RESOURCE_ROOT));
        module(packaged, APP, "demo = emptyTable(1)\n");
        Files.writeString(packaged.resolve("demo.index"), "demo.app\ndemo/10_demo.groovy\n");
        // Left over from an earlier run with other modules: must not survive.
        Files.createDirectories(workDir.resolve("app.d"));
        Files.writeString(workDir.resolve("app.d/stale.app"), "type=script\n");

        EmbedSettings settings = EmbedSettings.builder("dhc-a").appModules("demo").workDir(workDir).build();
        Path applicationDir;
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {classpathRoot.toUri().toURL()}, null)) {
            applicationDir = AppModules.prepare(settings, classLoader);
        }

        assertEquals(workDir.toAbsolutePath().resolve("app.d"), applicationDir);
        assertEquals(APP, Files.readString(applicationDir.resolve("demo.app")));
        assertTrue(Files.isRegularFile(applicationDir.resolve("demo/10_demo.groovy")));
        assertFalse(Files.exists(applicationDir.resolve("stale.app")));
        assertEquals(List.of(), AppModuleValidator.validate(applicationDir));
    }

    @Test
    void aModuleThatIsNotOnTheClasspathIsNamed(@TempDir Path classpathRoot, @TempDir Path workDir) throws IOException {
        EmbedSettings settings = EmbedSettings.builder("dhc-a").appModules("absent").workDir(workDir).build();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {classpathRoot.toUri().toURL()}, null)) {
            EmbeddedDeephavenException error =
                    assertThrows(EmbeddedDeephavenException.class, () -> AppModules.prepare(settings, classLoader));
            assertTrue(error.getMessage().contains("'absent' is not on the classpath"), error.getMessage());
            assertTrue(error.getMessage().contains("DHC_APP_MODULES"), error.getMessage());
        }
    }

    @Test
    void anExplicitDirectoryIsUsedAsItIs(@TempDir Path sourceDir, @TempDir Path workDir) throws IOException {
        module(sourceDir, APP, "demo = emptyTable(1)\n");
        EmbedSettings settings =
                EmbedSettings.builder("dhc-a").appModules("ignored").appDir(sourceDir).workDir(workDir).build();

        assertEquals(sourceDir.toAbsolutePath(), AppModules.prepare(settings, getClass().getClassLoader()));
        assertFalse(Files.exists(workDir.resolve("app.d")));
    }

    @Test
    void aDirectoryIsWrittenTheShorterWay() {
        Path checkout = Path.of("/a/long/way/down/to/the/checkout").toAbsolutePath();

        // Started from the checkout: the work directory below it is a few characters away.
        assertEquals(Path.of("build", "work", "app.d"),
                AppModules.shortest(checkout.resolve("build/work/app.d"), checkout));
        // Started from a sibling directory: still shorter than the whole path.
        assertEquals(Path.of("..", "..", "build", "work", "app.d"),
                AppModules.shortest(checkout.resolve("build/work/app.d"), checkout.resolve("apps/extracted")));
        // Somewhere else entirely: going up and down again is longer, so the path stays as it is.
        Path elsewhere = Path.of("/var/dhc/work/app.d").toAbsolutePath();
        assertEquals(elsewhere, AppModules.shortest(elsewhere, checkout));
        // The directory itself.
        assertEquals(Path.of("."), AppModules.shortest(checkout, checkout));
    }

    @Test
    void scriptsAreListedInTheOrderDeephavenRunsThem(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("b"));
        Files.createDirectories(directory.resolve("a"));
        Files.writeString(directory.resolve("b.app"),
                "type=script\nscriptType=groovy\nenabled=true\nid=b\nname=B\nfile_0=b/10.groovy\nfile_1=b/20.groovy\n");
        Files.writeString(directory.resolve("a.app"),
                "type=script\nscriptType=groovy\nenabled=true\nid=a\nname=A\nfile_0=a/10.groovy\n");
        Files.writeString(directory.resolve("off.app"),
                "type=script\nscriptType=groovy\nenabled=false\nid=off\nname=Off\nfile_0=off/10.groovy\n");
        Files.writeString(directory.resolve("other.app"), "type=static\nclass=x.Y\n");

        assertEquals(
                List.of(directory.resolve("a/10.groovy"), directory.resolve("b/10.groovy"),
                        directory.resolve("b/20.groovy")),
                AppModules.scripts(directory));
    }

    @Test
    void aScriptPathDeephavenCannotCompileIsRefusedWithItsLength(@TempDir Path directory) throws IOException {
        // Deep enough that the script's path passes the limit whatever the temporary directory is.
        Path deep = directory;
        while (deep.toString().length() <= AppModules.MAX_SCRIPT_PATH) {
            deep = deep.resolve("a-directory-with-a-long-name");
        }
        Files.createDirectories(deep.resolve("demo"));
        module(deep, APP, "demo = emptyTable(1)\n");
        Path applicationDir = deep;

        EmbeddedDeephavenException error =
                assertThrows(EmbeddedDeephavenException.class, () -> AppModules.checkScriptPaths(applicationDir));

        int length = deep.resolve("demo/10_demo.groovy").toString().length();
        assertTrue(error.getMessage().contains("has a path of " + length + " characters, and 215 is the longest"),
                error.getMessage());
        assertTrue(error.getMessage().contains("File name too long"), error.getMessage());
        assertTrue(error.getMessage().contains("DHC_WORK_DIR"), error.getMessage());
    }

    @Test
    void theSameScriptsPassWhenTheDirectoryIsWrittenRelativeToWhereTheJvmStarted(@TempDir Path directory)
            throws IOException {
        Path deep = directory;
        while (deep.toString().length() <= AppModules.MAX_SCRIPT_PATH) {
            deep = deep.resolve("a-directory-with-a-long-name");
        }
        Path workingDirectory = deep;
        Path applicationDir = Files.createDirectories(deep.resolve("build/work/app.d"));
        module(applicationDir, APP, "demo = emptyTable(1)\n");

        Path shortest = AppModules.shortest(applicationDir, workingDirectory);

        assertEquals(Path.of("build", "work", "app.d"), shortest);
        // The list is read through the absolute directory here, because this test's JVM was not
        // started in that deep directory; what is checked is the path Deephaven would be given.
        for (Path script : AppModules.scripts(applicationDir)) {
            Path given = shortest.resolve(applicationDir.relativize(script));
            assertTrue(given.toString().length() <= AppModules.MAX_SCRIPT_PATH, given.toString());
        }
    }
}
