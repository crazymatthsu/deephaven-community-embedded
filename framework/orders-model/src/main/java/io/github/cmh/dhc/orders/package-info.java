/**
 * The demo's domain, shared by both roles and by the tests: an execution
 * ({@link io.github.cmh.dhc.orders.ExecEvent}), the host's cache of order states
 * ({@link io.github.cmh.dhc.orders.OrderCache}), a deterministic source of executions
 * ({@link io.github.cmh.dhc.orders.MockExecGenerator}), the expected result
 * ({@link io.github.cmh.dhc.orders.ExecOracle}) and the table columns
 * ({@link io.github.cmh.dhc.orders.ExecTables}).
 *
 * <p>Plain Java. Only the table definitions touch Deephaven, and only its API jar.
 */
package io.github.cmh.dhc.orders;
