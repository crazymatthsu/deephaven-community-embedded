package io.github.cmh.dhc.apps.dhcb.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cmh.dhc.apps.dhcb.InstanceA;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the Spring host has to answer over HTTP when instance A has fed its fixed executions.
 * The same checks run against the application inside the test's JVM (T4) and against the
 * extracted layout running as a process (T5); they know only the application's address.
 */
final class HostChecks {

    private final String baseUrl;

    /** @param baseUrl the Spring MVC address, for example {@code http://localhost:8081} */
    HostChecks(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    JsonNode summary() {
        HttpResponse<String> response = Http.get(baseUrl + "/api/summary");
        assertEquals(200, response.statusCode(), response.body());
        return Http.json(response);
    }

    JsonNode health() {
        return Http.json(Http.get(baseUrl + "/actuator/health"));
    }

    /** Waits until B has every order A reports once its feed has reached the limit. */
    JsonNode awaitAllOrders() throws InterruptedException {
        return awaitSummary("all " + InstanceA.expectedOrders().size() + " orders with the feed connected",
                summary -> summary.path("orders").asLong() == InstanceA.expectedOrders().size()
                        && summary.path("cumQty").asLong() == expectedCumQty()
                        && "CONNECTED".equals(summary.path("feed").path("state").asText()));
    }

    JsonNode awaitSummary(String what, Predicate<JsonNode> reached) throws InterruptedException {
        long deadline = System.nanoTime() + InstanceA.PATIENCE.toNanos();
        JsonNode summary = summary();
        while (!reached.test(summary)) {
            if (System.nanoTime() > deadline) {
                fail("/api/summary did not reach " + what + " within " + InstanceA.PATIENCE + ". It says: " + summary);
            }
            Thread.sleep(200);
            summary = summary();
        }
        return summary;
    }

    JsonNode awaitHealth(String component, String status) throws InterruptedException {
        long deadline = System.nanoTime() + InstanceA.PATIENCE.toNanos();
        JsonNode health = health();
        while (!status.equals(health.path("components").path(component).path("status").asText())) {
            if (System.nanoTime() > deadline) {
                fail("/actuator/health did not report " + component + " " + status + " within " + InstanceA.PATIENCE
                        + ". It says: " + health);
            }
            Thread.sleep(200);
            health = health();
        }
        return health;
    }

    static long expectedCumQty() {
        return InstanceA.expectedCumBySymbol().stream().mapToLong(row -> (Long) row.get("CumQty")).sum();
    }

    static long expectedOpenOrders() {
        return InstanceA.expectedOrders().stream().filter(row -> ((Number) row.get("LeavesQty")).longValue() > 0).count();
    }

    /** Every check, in the order a reader would try them. */
    void all() throws InterruptedException {
        awaitAllOrders();
        summaryEqualsTheOracle();
        everyOrderIsServedAsAHasIt();
        anUnknownOrderIsNotFound();
        anIdIsTextAndNeverCode();
        healthNamesDeephavenAndTheUpstreamAsUp();
        thePageEmbedsTheTwoTablesFromDeephavensPort();
    }

    void summaryEqualsTheOracle() {
        JsonNode summary = summary();
        assertEquals(InstanceA.expectedOrders().size(), summary.path("orders").asLong(), "orders");
        assertEquals(expectedOpenOrders(), summary.path("openOrders").asLong(), "openOrders");
        assertEquals(expectedCumQty(), summary.path("cumQty").asLong(), "cumQty");
        assertFalse(summary.path("stale").asBoolean(), "stale while A is connected");
        assertEquals("dhc-a", summary.path("feed").path("upstream").asText());
        assertEquals("CONNECTED", summary.path("feed").path("state").asText());

        List<Map<String, Object>> expected = InstanceA.expectedCumBySymbol();
        JsonNode bySymbol = summary.path("bySymbol");
        assertEquals(expected.size(), bySymbol.size(), "symbols");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).get("Symbol"), bySymbol.get(i).path("symbol").asText(), "symbol " + i);
            assertEquals(expected.get(i).get("CumQty"), bySymbol.get(i).path("cumQty").asLong(), "cumQty of symbol " + i);
        }
    }

    void everyOrderIsServedAsAHasIt() {
        for (Map<String, Object> expected : InstanceA.expectedOrders()) {
            String id = (String) expected.get("OrderID");
            HttpResponse<String> response = Http.get(baseUrl + "/api/orders/" + id);
            assertEquals(200, response.statusCode(), "order " + id + ": " + response.body());
            JsonNode order = Http.json(response);
            assertEquals(expected.size(), order.size(), "fields of order " + id + ": " + order);
            expected.forEach((column, value) -> {
                JsonNode field = order.path(column);
                String where = column + " of order " + id;
                if (value instanceof Instant instant) {
                    assertEquals(instant, Instant.parse(field.asText()), where);
                } else if (value instanceof Double number) {
                    assertEquals(number, field.asDouble(), where);
                } else if (value instanceof Number number) {
                    assertTrue(field.isIntegralNumber(), where + " is not a whole number: " + field);
                    assertEquals(number.longValue(), field.asLong(), where);
                } else {
                    assertEquals(value, field.asText(), where);
                }
            });
        }
    }

    void anUnknownOrderIsNotFound() {
        HttpResponse<String> response = Http.get(baseUrl + "/api/orders/O9999999");
        assertEquals(404, response.statusCode(), response.body());
        assertEquals("There is no order O9999999", Http.json(response).path("error").asText());
    }

    /** The id reaches the engine as a value to compare with, not as part of a formula. */
    void anIdIsTextAndNeverCode() {
        // x`); System.exit(1)  -- what a formula built by string concatenation would have run
        HttpResponse<String> response = Http.get(baseUrl + "/api/orders/x%60%29%3B%20System.exit%281%29");
        assertEquals(404, response.statusCode(), response.body());
        assertEquals(200, Http.get(baseUrl + "/api/summary").statusCode(), "the application is still there");
    }

    void healthNamesDeephavenAndTheUpstreamAsUp() {
        HttpResponse<String> response = Http.get(baseUrl + "/actuator/health");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode health = Http.json(response);
        assertEquals("UP", health.path("status").asText(), response.body());
        JsonNode deephaven = health.path("components").path("deephaven");
        assertEquals("UP", deephaven.path("status").asText(), response.body());
        assertEquals("dhc-b", deephaven.path("details").path("appId").asText(), response.body());
        JsonNode upstream = health.path("components").path("dhc-a");
        assertEquals("UP", upstream.path("status").asText(), response.body());
        assertEquals("CONNECTED", upstream.path("details").path("state").asText(), response.body());
        assertEquals(2, upstream.path("details").path("subscriptions").asInt(), response.body());
    }

    void thePageEmbedsTheTwoTablesFromDeephavensPort() {
        HttpResponse<String> page = Http.get(baseUrl + "/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<iframe id=\"orders_a\""), "no widget for orders_a on the page");
        assertTrue(page.body().contains("<iframe id=\"cum_by_symbol\""), "no widget for cum_by_symbol on the page");

        JsonNode embedding = Http.json(Http.get(baseUrl + "/api/deephaven"));
        int deephavenPort = embedding.path("port").asInt();
        assertEquals("[\"orders_a\",\"cum_by_symbol\"]", embedding.path("tables").toString());
        // What the page sets as the source of a widget: Deephaven's own port, not the application's.
        String widget = "http://localhost:" + deephavenPort + "/iframe/widget/?name=orders_a";
        assertEquals(200, Http.get(widget).statusCode(), widget);
        assertFalse(baseUrl.endsWith(":" + deephavenPort), "Deephaven and Spring MVC share a port");
    }
}
