package io.github.cmh.dhc.apps.dhcb.spring;

import java.nio.file.Path;

/** Where the build put this application's two packagings; it passes both as system properties. */
final class PackagedHost {

    /** {@code build/extracted}: the thin jar, {@code lib/} and {@code jvm.args} (F7). */
    static final Path EXTRACTED = Path.of(System.getProperty("dhc.b.extracted"));

    /** Spring Boot's fat jar, which Deephaven cannot run from (F6). */
    static final Path FAT_JAR = Path.of(System.getProperty("dhc.b.fatJar"));

    private PackagedHost() {}
}
