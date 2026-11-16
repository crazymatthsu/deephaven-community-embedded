package io.github.cmh.dhc.apps.dhca.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cmh.dhc.apps.dhca.RoleA;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.orders.ExecOracle;
import io.github.cmh.dhc.orders.ExecTables;
import io.github.cmh.dhc.orders.OrderState;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T4: role A in a Spring Boot host. The feed is the fixed one of the other tests, so what the
 * tables and the REST API hold at the end is known exactly.
 */
@SpringBootTest(classes = DhcASpringApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dhc.http.port=0",
                "dhc.auth.mode=anonymous",
                "dhc.gen.seed=42",
                "dhc.gen.rate=2000",
                "dhc.gen.orders=12",
                "dhc.gen.event.limit=600",
                "dhc.gen.start.time=2026-01-05T14:30:00Z",
        })
class DhcASpringAppTest {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void workDir(DynamicPropertyRegistry registry) throws IOException {
        String directory = Files.createTempDirectory("dhc-a-spring-test-").toString();
        registry.add("dhc.work.dir", () -> directory);
    }

    @LocalServerPort
    int port;

    @Autowired
    EmbeddedDeephaven deephaven;

    @Autowired
    RoleA role;

    private final ObjectMapper json = new ObjectMapper();
    private ExecOracle oracle;

    @BeforeEach
    void waitForTheFeed() throws InterruptedException {
        assertTrue(role.feed().awaitLimit(PATIENCE), "the feed did not reach its limit");
        oracle = ExecOracle.after(role.settings().generatorConfig(), 600);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void theModulesTablesEqualTheOracle() {
        List<Map<String, Object>> orders = oracle.lastExecByOrder().values().stream().map(ExecTables::row).toList();
        TableAssertions.awaitRows(deephaven, deephaven.table("orders"), orders, PATIENCE);

        List<Map<String, Object>> cache = oracle.stateByOrder().values().stream().map(ExecTables::row).toList();
        TableAssertions.awaitSize(deephaven, deephaven.table("order_cache"), cache.size(), PATIENCE);
        assertEquals(cache.size(), TableAssertions.size(deephaven, deephaven.table("order_cache")));
    }

    @Test
    void theRestApiAnswersFromTheApplicationsOwnCache() throws Exception {
        Map<String, OrderState> expected = oracle.stateByOrder();
        JsonNode summary = json.readTree(get("/api/summary").body());
        assertEquals(expected.size(), summary.path("orders").asLong());
        assertEquals(600, summary.path("executions").asLong());
        assertEquals(expected.values().stream().mapToLong(OrderState::cumQty).sum(), summary.path("cumQty").asLong());
        assertEquals(expected.values().stream().filter(order -> order.leavesQty() > 0).count(),
                summary.path("openOrders").asLong());

        OrderState any = expected.values().iterator().next();
        HttpResponse<String> found = get("/api/orders/" + any.orderId());
        assertEquals(200, found.statusCode(), found.body());
        JsonNode order = json.readTree(found.body());
        assertEquals(any.symbol(), order.path("symbol").asText());
        assertEquals(any.cumQty(), order.path("cumQty").asLong());
        assertEquals(404, get("/api/orders/O9999999").statusCode());
    }

    @Test
    void healthAndItsGroupsIncludeDeephaven() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            JsonNode health = json.readTree(response.body());
            assertEquals("UP", health.path("status").asText(), path + ": " + response.body());
            assertEquals("UP", health.path("components").path("deephaven").path("status").asText(),
                    path + ": " + response.body());
        }
    }
}
