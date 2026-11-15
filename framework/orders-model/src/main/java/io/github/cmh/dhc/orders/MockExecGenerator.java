package io.github.cmh.dhc.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Makes up order executions: a fixed number of orders is open at any time, each call fills part
 * of one of them, and a filled order is replaced by a new one. The stream never ends.
 *
 * <p>Deterministic: everything, {@code TransactTime} included, follows from the
 * {@link GeneratorConfig}. The generator does not look at the clock and does not wait; pacing the
 * events is the caller's business.
 *
 * <p>Not thread-safe.
 */
public final class MockExecGenerator {

    /** Symbols and the prices their fills move around. Invented, like everything here. */
    private static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "TSLA", "JPM", "XOM"};
    private static final double[] BASE_PRICES = {190.0, 410.0, 140.0, 180.0, 120.0, 250.0, 200.0, 110.0};

    private static final int LOT = 100;

    private final GeneratorConfig config;
    private final SplittableRandom random;
    private final List<WorkingOrder> open = new ArrayList<>();
    private long nextOrderNumber = 1;
    private long seq;

    public MockExecGenerator(GeneratorConfig config) {
        this.config = config;
        this.random = new SplittableRandom(config.seed());
        for (int i = 0; i < config.openOrders(); i++) {
            open.add(newOrder());
        }
    }

    /** The next execution. */
    public ExecEvent next() {
        seq++;
        int slot = random.nextInt(open.size());
        WorkingOrder order = open.get(slot);

        long leavesBefore = order.orderQty - order.cumQty;
        long lastQty = Math.min(leavesBefore, (long) (1 + random.nextInt(4)) * LOT);
        // Within a tenth of a percent of the symbol's base price, in whole cents.
        double lastPx = Math.round(order.basePrice * (1 + (random.nextDouble() - 0.5) * 0.002) * 100) / 100.0;
        order.cumQty += lastQty;
        long leavesQty = order.orderQty - order.cumQty;

        ExecEvent event = new ExecEvent(
                String.format("E%09d", seq),
                order.orderId,
                order.symbol,
                order.side,
                lastQty,
                lastPx,
                order.cumQty,
                leavesQty,
                leavesQty == 0 ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED,
                config.startTime().plus(config.interval().multipliedBy(seq - 1)),
                seq);
        if (leavesQty == 0) {
            open.set(slot, newOrder());
        }
        return event;
    }

    private WorkingOrder newOrder() {
        int symbol = random.nextInt(SYMBOLS.length);
        WorkingOrder order = new WorkingOrder();
        order.orderId = String.format("O%07d", nextOrderNumber++);
        order.symbol = SYMBOLS[symbol];
        order.basePrice = BASE_PRICES[symbol];
        order.side = random.nextBoolean() ? Side.BUY : Side.SELL;
        order.orderQty = (long) (1 + random.nextInt(10)) * LOT;
        return order;
    }

    private static final class WorkingOrder {
        String orderId;
        String symbol;
        double basePrice;
        Side side;
        long orderQty;
        long cumQty;
    }
}
