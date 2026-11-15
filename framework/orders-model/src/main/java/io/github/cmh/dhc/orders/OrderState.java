package io.github.cmh.dhc.orders;

import java.time.Instant;

/**
 * What is known about one order after its executions so far: one entry of the {@link OrderCache},
 * and one row of the {@code order_cache} table ({@link ExecTables#ORDER_CACHE}).
 *
 * @param orderQty size of the order
 * @param cumQty shares filled so far
 * @param leavesQty shares still open
 * @param avgPx average price of the fills, {@code notional / cumQty}
 * @param notional sum of price times quantity over the fills, in the order they happened
 * @param execCount number of fills
 * @param transactTime time of the latest fill
 * @param seq feed position of the latest fill
 */
public record OrderState(
        String orderId,
        String symbol,
        Side side,
        long orderQty,
        long cumQty,
        long leavesQty,
        double avgPx,
        double notional,
        OrdStatus ordStatus,
        long execCount,
        Instant transactTime,
        long seq) {

    /** The state after one more execution; {@code previous} is null for an order's first. */
    static OrderState after(OrderState previous, ExecEvent event) {
        double notional = (previous == null ? 0.0 : previous.notional) + event.lastPx() * event.lastQty();
        return new OrderState(
                event.orderId(),
                event.symbol(),
                event.side(),
                event.cumQty() + event.leavesQty(),
                event.cumQty(),
                event.leavesQty(),
                notional / event.cumQty(),
                notional,
                event.ordStatus(),
                (previous == null ? 0 : previous.execCount) + 1,
                event.transactTime(),
                event.seq());
    }
}
