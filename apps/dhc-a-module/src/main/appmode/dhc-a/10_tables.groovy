// Role A: the tables this instance shows. Everything with logic is Java, lent by the host as
// `host.<name>` (io.github.cmh.dhc.apps.dhca.RoleA); this script only says what is shown.

import io.deephaven.engine.table.impl.sources.ring.RingTableTools

// The host's source of executions: generator -> OrderCache -> here.
feed = host.execFeed

// One row per execution, visible for the update cycle it arrived in.
exec_events = feed.blinkTable()

// The most recent executions, as many as DHC_RING_CAPACITY says.
execs = RingTableTools.of(exec_events, host.settings.ringCapacity())

// The latest execution of every order.
orders = exec_events.lastBy("OrderID")

// The host's own cache as a live table: one row per order, as the cache has it.
order_cache = host.orderCache.asTable()

// What the cache says about itself: size, updates, updates per second, oldest entry.
cache_stats = host.orderCache.statsTable()
