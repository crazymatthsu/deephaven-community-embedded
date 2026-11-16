package io.github.cmh.dhc.apps.dhcb.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The application's own HTTP API, answered from role B's tables. */
@RestController
@RequestMapping("/api")
public class OrdersController {

    private final OrdersService orders;

    public OrdersController(OrdersService orders) {
        this.orders = orders;
    }

    /** One order's latest state, with the column names A uses; 404 when A has no such order. */
    @GetMapping("/orders/{id}")
    public ResponseEntity<Map<String, Object>> order(@PathVariable("id") String id) {
        return orders.order(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("There is no order " + id)));
    }

    @GetMapping("/summary")
    public OrdersService.Summary summary() {
        return orders.summary();
    }

    /** For the page at {@code /}: where this instance's Deephaven listens and which tables to show. */
    @GetMapping("/deephaven")
    public Map<String, Object> deephaven() {
        return orders.embedding();
    }

    @ExceptionHandler(FeedUnavailableException.class)
    ResponseEntity<Map<String, Object>> feedUnavailable(FeedUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(e.getMessage()));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
