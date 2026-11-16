package io.github.cmh.dhc.apps.dhcb.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cmh.dhc.apps.dhcb.InstanceA;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T4: instance A goes away under a running Spring host and comes back. The application stays up
 * and says truthfully what it still knows; the {@code dhc-a} health indicator follows A.
 */
@SpringBootTest(classes = DhcBSpringApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpstreamDownTest {

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

    @Test
    void theApplicationOutlivesItsUpstream() throws InterruptedException {
        String baseUrl = "http://localhost:" + port;
        HostChecks host = new HostChecks(baseUrl);
        host.awaitAllOrders();
        host.healthNamesDeephavenAndTheUpstreamAsUp();
        String anOrder = (String) InstanceA.expectedOrders().get(0).get("OrderID");
        // What a page and a monitoring system do all the time, also while B loses A and finds it
        // again: ask for the summary and for health. Not one of these requests may hang.
        Clients clients = Clients.start(baseUrl);

        a.kill();

        // The indicator for A goes down, and with it the overall status. Deephaven itself is up.
        JsonNode down = host.awaitHealth("dhc-a", "DOWN");
        assertEquals("DISCONNECTED", down.path("components").path("dhc-a").path("details").path("state").asText(),
                down.toString());
        assertEquals("UP", down.path("components").path("deephaven").path("status").asText(), down.toString());
        assertEquals("DOWN", down.path("status").asText(), down.toString());
        assertEquals(503, Http.get(baseUrl + "/actuator/health").statusCode());
        // Two narrower questions. "Is this process well" stays yes, so nothing restarts it for
        // A's sake; "can it serve what it is for" is no.
        assertEquals(200, Http.get(baseUrl + "/actuator/health/liveness").statusCode(), "liveness while A is away");
        assertEquals(503, Http.get(baseUrl + "/actuator/health/readiness").statusCode(), "readiness while A is away");

        // The application still answers. The summary is the last one it saw, and says so.
        JsonNode stale = host.summary();
        assertTrue(stale.path("stale").asBoolean(), stale.toString());
        assertEquals("DISCONNECTED", stale.path("feed").path("state").asText(), stale.toString());
        assertEquals(InstanceA.expectedOrders().size(), stale.path("orders").asLong(), stale.toString());
        assertEquals(HostChecks.expectedCumQty(), stale.path("cumQty").asLong(), stale.toString());
        // A lookup needs a live table, so it is refused with the reason and not answered from old rows.
        HttpResponse<String> lookup = Http.get(baseUrl + "/api/orders/" + anOrder);
        assertEquals(503, lookup.statusCode(), lookup.body());
        assertTrue(Http.json(lookup).path("error").asText().startsWith("Instance dhc-a is DISCONNECTED"), lookup.body());
        assertEquals(200, Http.get(baseUrl + "/").statusCode(), "the page");

        a.start();

        JsonNode up = host.awaitHealth("dhc-a", "UP");
        assertEquals(200, Http.get(baseUrl + "/actuator/health/readiness").statusCode(), "readiness when A is back");
        assertEquals(1, up.path("components").path("dhc-a").path("details").path("reconnects").asInt(), up.toString());
        assertEquals("UP", host.awaitHealth("deephaven", "UP").path("status").asText());
        // A came back empty and fed the same executions again; B's rebuilt tables hold them.
        JsonNode again = host.awaitAllOrders();
        assertEquals(1, again.path("feed").path("reconnects").asLong(), again.toString());
        host.summaryEqualsTheOracle();
        host.everyOrderIsServedAsAHasIt();

        clients.stopAndCheck();
    }

    /** Two clients that never stop asking, and remember the slowest answer and the first failure. */
    private static final class Clients {

        private final List<Thread> threads = new ArrayList<>();
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong slowestMillis = new AtomicLong();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private volatile boolean running = true;

        static Clients start(String baseUrl) {
            Clients clients = new Clients();
            for (String path : List.of("/api/summary", "/actuator/health")) {
                Thread thread = new Thread(() -> clients.keepAsking(baseUrl + path), "client-of-" + path);
                thread.setDaemon(true);
                thread.start();
                clients.threads.add(thread);
            }
            return clients;
        }

        private void keepAsking(String url) {
            while (running) {
                long asked = System.nanoTime();
                try {
                    int status = Http.get(url).statusCode();
                    // Health answers 503 while A is away; that is an answer.
                    if (status != 200 && status != 503) {
                        failure.compareAndSet(null, url + " answered " + status);
                    }
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, url + " did not answer: " + e);
                }
                slowestMillis.accumulateAndGet((System.nanoTime() - asked) / 1_000_000, Math::max);
                requests.incrementAndGet();
            }
        }

        void stopAndCheck() throws InterruptedException {
            running = false;
            for (Thread thread : threads) {
                thread.join(30_000);
                assertTrue(!thread.isAlive(), thread.getName() + " is still waiting for an answer");
            }
            assertNull(failure.get(), "a request failed while A was going away and coming back");
            assertTrue(requests.get() > 50, "only " + requests.get() + " requests were made");
            assertTrue(slowestMillis.get() < 5_000, "the slowest answer took " + slowestMillis.get() + " ms");
        }
    }
}
