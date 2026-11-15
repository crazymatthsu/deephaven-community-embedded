package io.github.cmh.dhc.apps.dhca.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deephaven.engine.table.Table;
import io.deephaven.engine.util.TableTools;
import io.github.cmh.dhc.embed.EmbeddedDeephavenException;
import io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** T2: the {@code hello} module, run by an embedded engine, read from host code. */
class HelloModuleTest {

    @RegisterExtension
    static final EmbeddedDeephavenExtension deephaven =
            EmbeddedDeephavenExtension.forApp("dhc-a").modules("hello").bind("greeting", "hello from the test").build();

    @Test
    void theHelloTableTicksAndCarriesTheHostsGreeting() {
        Table hello = deephaven.table("hello");

        TableAssertions.assertColumns(hello, "Timestamp", "Row", "Message");
        TableAssertions.awaitSize(deephaven.get(), hello, 2, Duration.ofSeconds(30));
        List<String> messages = TableAssertions.column(deephaven.get(), hello, "Message");
        assertTrue(messages.size() >= 2, "rows read: " + messages);
        assertEquals(List.of("hello from the test"), messages.stream().distinct().toList());
    }

    @Test
    void hostCodeCanBuildTablesThroughTheEngine() {
        // F4: without engine() this fails with "May not initiate serial table operations".
        long rows = deephaven.engine().call(() -> TableTools.emptyTable(5).update("X = ii * 2").size());

        assertEquals(5, rows);
    }

    @Test
    void anUnknownTableIsReportedWithTheNamesThatExist() {
        EmbeddedDeephavenException error = assertThrows(EmbeddedDeephavenException.class, () -> deephaven.table("nope"));

        assertTrue(error.getMessage().contains("There is no table 'nope'"), error.getMessage());
        assertTrue(error.getMessage().contains("hello"), error.getMessage());
    }

    @Test
    void itListensOnAFreePortOfItsOwn() {
        assertTrue(deephaven.get().httpPort() > 0, "port: " + deephaven.get().httpPort());
        assertTrue(deephaven.get().isRunning());
    }
}
