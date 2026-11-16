// Role B: the tables this instance shows. They are built on tables of instance A, which this
// instance subscribes to; A's address and key come from the host's settings.
//
// This script is run again whenever the connection to A has been lost and made anew, so it has
// to be a list of definitions and nothing else: no state, no side effects.

// Instance A, as the host knows it (DHC_UPSTREAM_DHC_A_URL).
a = host.remote("dhc-a")

// Live copies of two of A's tables.
execs_a = a.subscribe("execs")
orders_a = a.subscribe("orders")

// What B adds: tables A does not have.
cum_by_symbol = orders_a.view("Symbol", "CumQty").sumBy("Symbol")
exec_rate = execs_a.updateView("Bin = lowerBin(TransactTime, MINUTE)").countBy("Execs", "Bin")
open_orders = orders_a.where("LeavesQty > 0")

// Is A connected, since when, how often has the connection been made anew. This table is B's
// own, so it keeps working while A is down.
feed_status = a.statusTable()
