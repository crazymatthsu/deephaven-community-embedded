package io.github.cmh.dhc.apps.dhca.spring;

import io.github.cmh.dhc.apps.dhca.RoleA;
import io.github.cmh.dhc.orders.OrderCache;
import io.github.cmh.dhc.orders.OrderState;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application's own HTTP API, answered from the application's own state: the order cache,
 * a plain Java object that knows nothing about Deephaven. Deephaven shows the same cache as the
 * table {@code order_cache}; neither is the other's source.
 */
@RestController
@RequestMapping("/api")
public class OrderCacheController {

    private final RoleA role;

    public OrderCacheController(RoleA role) {
        this.role = role;
    }

    /** One order as the cache holds it; 404 when the cache has none. */
    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderState> order(@PathVariable("id") String id) {
        return ResponseEntity.of(role.cache().get(id));
    }

    @GetMapping("/summary")
    public Summary summary() {
        OrderCache cache = role.cache();
        List<OrderState> orders = cache.snapshot();
        long cumQty = 0;
        long open = 0;
        for (OrderState order : orders) {
            cumQty += order.cumQty();
            if (order.leavesQty() > 0) {
                open++;
            }
        }
        return new Summary(orders.size(), open, cumQty, role.feed().fed());
    }

    /**
     * @param orders orders in the cache
     * @param openOrders of those, the ones not fully filled
     * @param cumQty filled quantity over all orders
     * @param executions executions the feed has produced so far
     */
    public record Summary(long orders, long openOrders, long cumQty, long executions) {
    }
}
