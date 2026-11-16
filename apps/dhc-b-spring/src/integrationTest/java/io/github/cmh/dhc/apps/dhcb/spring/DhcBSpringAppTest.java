package io.github.cmh.dhc.apps.dhcb.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cmh.dhc.apps.dhcb.InstanceA;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T4: the Spring Boot host with role B, in the test's JVM, beside instance A in a process of its
 * own. This is also the first run of Spring on the library versions pinned to Deephaven's (F9).
 */
@SpringBootTest(classes = DhcBSpringApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DhcBSpringAppTest {

    static final InstanceA a = InstanceA.anonymous();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        String workDir = Files.createTempDirectory("dhc-b-spring-test-").toString();
        registry.add("dhc.upstream.dhc.a.url", a::url);
        registry.add("dhc.http.port", () -> "0");
        registry.add("dhc.auth.mode", () -> "anonymous");
        registry.add("dhc.work.dir", () -> workDir);
    }

    @AfterAll
    static void stopA() {
        a.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    EmbeddedDeephaven deephaven;

    private HostChecks host;

    @BeforeEach
    void waitForTheFeed() throws InterruptedException {
        host = new HostChecks("http://localhost:" + port);
        host.awaitAllOrders();
    }

    @Test
    void theApplicationIsRoleB() {
        assertTrue(deephaven.isRunning());
        assertEquals("dhc-b", deephaven.settings().appId());
        assertEquals(java.util.List.of("dhc-b"), deephaven.settings().appModules());
        assertEquals(java.util.Set.of("dhc-a"), deephaven.upstreamIds());
    }

    @Test
    void summaryEqualsTheOracle() {
        host.summaryEqualsTheOracle();
    }

    @Test
    void everyOrderIsServedAsAHasIt() {
        host.everyOrderIsServedAsAHasIt();
    }

    @Test
    void anUnknownOrderIsNotFound() {
        host.anUnknownOrderIsNotFound();
    }

    @Test
    void anIdIsTextAndNeverCode() {
        host.anIdIsTextAndNeverCode();
    }

    @Test
    void healthNamesDeephavenAndTheUpstreamAsUp() {
        host.healthNamesDeephavenAndTheUpstreamAsUp();
    }

    @Test
    void thePageEmbedsTheTwoTablesFromDeephavensPort() {
        host.thePageEmbedsTheTwoTablesFromDeephavensPort();
    }
}
