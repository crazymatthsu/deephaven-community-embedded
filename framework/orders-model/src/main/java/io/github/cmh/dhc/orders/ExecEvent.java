package io.github.cmh.dhc.orders;

import java.time.Instant;

/**
 * One execution (a fill) of an order. The components are the FIX 4.2 fields of the same names,
 * and the columns of the {@code exec_events} table ({@link ExecTables#EXEC_EVENTS}).
 *
 * @param execId unique per execution
 * @param orderId the order that was filled
 * @param lastQty shares filled by this execution
 * @param lastPx price of this execution
 * @param cumQty shares of the order filled so far, this execution included
 * @param leavesQty shares of the order still open after this execution
 * @param ordStatus state of the order after this execution
 * @param transactTime when the execution happened
 * @param seq position in the feed, from 1, without gaps
 */
public record ExecEvent(
        String execId,
        String orderId,
        String symbol,
        Side side,
        long lastQty,
        double lastPx,
        long cumQty,
        long leavesQty,
        OrdStatus ordStatus,
        Instant transactTime,
        long seq) {
}
