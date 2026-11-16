package io.github.cmh.dhc.embed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T1: the checks made before Deephaven is touched. No engine involved. */
class PreflightTest {

    @Test
    void theTestJvmHasTheMandatoryFlags() {
        // build-logic puts them on every test task; this is the same check a host makes at start.
        assertEquals(List.of(), Preflight.missingJvmFlags());
    }

    @Test
    void missingFlagsAreNamedAlongWithTheWholeSet() {
        List<String> missing = List.of(Preflight.MANDATORY_JVM_FLAGS.get(2));

        EmbeddedDeephavenException error =
                assertThrows(EmbeddedDeephavenException.class, () -> Preflight.checkJvmFlags(missing));

        assertTrue(error.getMessage().contains("without 1 of the 3 flags"), error.getMessage());
        for (String flag : Preflight.MANDATORY_JVM_FLAGS) {
            assertTrue(error.getMessage().contains(flag), error.getMessage());
        }
    }

    @Test
    void runningFromInsideAFatJarIsRefusedWithTheReason() {
        for (String location : List.of(
                "jar:nested:/opt/app/app.jar/!BOOT-INF/lib/embed-core-0.1.0.jar!/",
                "jar:file:/opt/app/app.jar!/BOOT-INF/lib/embed-core-0.1.0.jar!/")) {
            EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                    () -> Preflight.checkNotNestedJar(location), location);
            assertTrue(error.getMessage().contains("cannot run from inside a fat jar"), error.getMessage());
            assertTrue(error.getMessage().contains("extract"), error.getMessage());
        }
    }

    @Test
    void anOrdinaryJarOrDirectoryIsFine() {
        assertDoesNotThrow(() -> Preflight.checkNotNestedJar("file:/opt/app/lib/embed-core-0.1.0.jar"));
        assertDoesNotThrow(() -> Preflight.checkNotNestedJar("file:/work/build/classes/java/main/"));
        assertDoesNotThrow(() -> Preflight.checkNotNestedJar(null));
    }

    @Test
    void aPortThatIsTakenIsReportedWithTheSettingToChange() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
            EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class,
                    () -> Preflight.checkPortFree("localhost", taken.getLocalPort()));

            assertTrue(error.getMessage().contains("Port " + taken.getLocalPort()), error.getMessage());
            assertTrue(error.getMessage().contains("DHC_HTTP_PORT"), error.getMessage());
        }
    }

    @Test
    void onWindowsDeephavensStorageDirectoriesAreNamedWithoutTheLeadingSlash() {
        // F11. The effect is checked by the Windows CI job, where the server would not start otherwise.
        assertEquals(Optional.of("layouts,notebooks"), EmbeddedDeephaven.storagePathDefaults('\\'));
        assertEquals(Optional.empty(), EmbeddedDeephaven.storagePathDefaults('/'));
    }

    @Test
    void portZeroMeansPickOneAndIsNotChecked() {
        assertDoesNotThrow(() -> Preflight.checkPortFree("localhost", 0));
    }

    @Test
    void aJavaWithoutACompilerIsRefusedWithWhatToUseInstead() {
        Preflight.checkCompiler(true);

        EmbeddedDeephavenException error =
                assertThrows(EmbeddedDeephavenException.class, () -> Preflight.checkCompiler(false));
        assertTrue(error.getMessage().contains("it needs a JDK"), error.getMessage());
        assertTrue(error.getMessage().contains("DHC_CONTAINER_BASE_IMAGE"), error.getMessage());
    }
}
