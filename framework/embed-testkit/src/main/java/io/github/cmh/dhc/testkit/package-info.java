/**
 * Test support for embedded Deephaven: {@link io.github.cmh.dhc.testkit.EmbeddedDeephavenExtension}
 * starts an instance for a test class, {@link io.github.cmh.dhc.testkit.TableAssertions} reads and
 * checks tables, {@link io.github.cmh.dhc.testkit.LiveThreads} finds threads left behind,
 * {@link io.github.cmh.dhc.testkit.ChildApp} runs another application as a process of its own, and
 * {@link io.github.cmh.dhc.testkit.Probe} looks at a running instance from outside.
 */
package io.github.cmh.dhc.testkit;
