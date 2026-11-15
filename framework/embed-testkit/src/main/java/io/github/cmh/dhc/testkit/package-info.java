/**
 * Test support for embedded Deephaven: {@link io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension}
 * starts an instance for a test class, {@link io.github.cmh.dhc.testkit.TableAssertions} reads and
 * checks tables, {@link io.github.cmh.dhc.testkit.LiveThreads} finds threads left behind.
 *
 * <p>The child-process launcher and the probe client of design section 4.4 arrive with the
 * phases that need them (P3 and P4).
 */
package io.github.cmh.dhc.testkit;
