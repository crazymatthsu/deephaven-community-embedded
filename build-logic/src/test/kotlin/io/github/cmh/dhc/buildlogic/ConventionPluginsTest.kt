package io.github.cmh.dhc.buildlogic

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs the fixture builds under `src/test/fixtures` and checks that the rules from the
 * feasibility spike hold, and that breaking one fails the build with a message that says why.
 */
class ConventionPluginsTest {

    // F3, stated here on its own on purpose: the test must not read the list it is checking.
    private val mandatoryJvmFlags = listOf(
        "--add-exports=java.management/sun.management=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )

    @Test
    fun `a module that declares its own repository fails the build`() {
        val result = fixture("own-repository", "help").buildAndFail()

        assertContains(result, "prefer settings repositories over project repositories")
        assertContains(result, "rogue")
    }

    @Test
    fun `a mirror that needs a login gets the one login on every repository`() {
        // Made-up values: the fixture resolves nothing, so they are never sent anywhere.
        val result = fixture("repo-login", "help", "-PdhcRepoUsername=fixture-user", "-PdhcRepoPassword=not-a-real-password")
            .build()

        val mirror = "https://artifactory.example.com/artifactory"
        assertContains(result, "plugins dhcPlugins $mirror/gradle-plugins-remote/ user=fixture-user password=set")
        assertContains(result, "plugins dhcMaven $mirror/maven-remote/ user=fixture-user password=set")
        assertContains(result, "dependencies dhcMaven $mirror/maven-remote/ user=fixture-user password=set")
        assertFalse(result.output.contains("not-a-real-password"), "the password must not appear in the build output")
    }

    @Test
    fun `a mirror that needs a login fails the build when the login is not given`() {
        val result = fixture("repo-login", "help").buildAndFail()

        assertContains(result, "DHC_REPO_CREDENTIALS=gradle-properties")
        assertContains(result, "the Gradle property dhcRepoUsername is not set")
    }

    @Test
    fun `verifyDeephavenAlignment passes for a plain Java host`() {
        val result = fixture("java-host", ":app:verifyDeephavenAlignment").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:verifyDeephavenAlignment")?.outcome)
    }

    @Test
    fun `verifyDeephavenAlignment fails when an older Jetty is forced`() {
        val result = fixture("java-host", ":app:verifyDeephavenAlignment", "-PforceOlderJetty").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyDeephavenAlignment")?.outcome)
        assertContains(result, "resolve to a different version than Deephaven alone resolves them to")
        assertContains(result, "org.eclipse.jetty:jetty-server  application: 12.0.36  Deephaven alone: 12.1.11")
    }

    @Test
    fun `verifyDeephavenAlignment fails when the application has no Deephaven at all`() {
        val result = fixture("java-host", ":app:verifyDeephavenAlignment", "-PwithoutDeephaven").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyDeephavenAlignment")?.outcome)
        assertContains(result, "has no io.deephaven module on its runtime classpath")
    }

    @Test
    fun `a plain Java host has the mandatory JVM flags on run, tests and start scripts`() {
        val result = fixture("java-host", ":app:printJvmFlags").build()

        assertFlags(result, "run", "test", "startScripts")
    }

    @Test
    fun `the start scripts of a plain Java host name one jar, which lists the others`() {
        val result = fixture("java-host", ":app:printStartClasspath").build()

        // Close to three hundred jars listed one by one are more than Windows accepts.
        assertContains(result, "unix: CLASSPATH=\$APP_HOME/lib/app.jar")
        assertContains(result, "windows: set CLASSPATH=%APP_HOME%\\lib\\app.jar")
        assertContains(result, "manifest has the server: true")
        val entries = Regex("manifest entries: (\\d+)").find(result.output)!!.groupValues[1].toInt()
        assertTrue(entries > 100, "the application jar lists only $entries libraries")
        val longest = Regex("longest windows line: (\\d+)").find(result.output)!!.groupValues[1].toInt()
        assertTrue(longest < 8191, "a line of the .bat has $longest characters; cmd accepts 8,191")
    }

    @Test
    fun `a Spring host resolves to Deephaven's versions without Kafka and without Tomcat WebSocket`() {
        val result = fixture(
            "spring-host", ":app:verifyDeephavenAlignment", ":app:dependencies", "--configuration", "runtimeClasspath"
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:verifyDeephavenAlignment")?.outcome)
        // Spring MVC on Tomcat is there, so the two absences below mean something.
        assertContains(result, "org.apache.tomcat.embed:tomcat-embed-core")
        assertContains(result, "io.deephaven:deephaven-server-jetty")
        assertFalse(result.output.contains("tomcat-embed-websocket"), "tomcat-embed-websocket is on the classpath (F8)")
        assertFalse(result.output.contains("deephaven-extensions-kafka"), "the Kafka extension is on the classpath (F2)")
        // Spring Boot 3.5 manages Jetty 12.0.x and a newer Jackson; Deephaven's versions win both ways (F9).
        assertContains(result, "org.eclipse.jetty:jetty-server:12.0.36 -> 12.1.11")
        assertFalse(
            Regex("""jackson-databind:\S+ -> (?!2\.21\.2\b)""").containsMatchIn(result.output),
            "jackson-databind does not resolve to the version Deephaven alone resolves",
        )
    }

    @Test
    fun `verifyDeephavenAlignment fails in a Spring host when an older Jetty is forced`() {
        val result = fixture("spring-host", ":app:verifyDeephavenAlignment", "-PforceOlderJetty").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":app:verifyDeephavenAlignment")?.outcome)
        assertContains(result, "org.eclipse.jetty:jetty-server  application: 12.0.36  Deephaven alone: 12.1.11")
    }

    @Test
    fun `a Spring host has the mandatory JVM flags on bootRun and tests`() {
        val result = fixture("spring-host", ":app:printJvmFlags").build()

        assertFlags(result, "bootRun", "test")
    }

    @Test
    fun `extractBootJar leaves a thin jar that lists its libraries, the libraries and the JVM flags`() {
        val result = fixture("spring-host", ":app:printExtracted").build()

        // F7: no jar within a jar, so javac can read every class when Deephaven compiles a formula.
        assertContains(result, "extracted: [app.jar, jvm.args, lib]")
        assertContains(result, "main class: fixture.Main")
        assertContains(result, "jars inside the thin jar: 0")
        assertContains(result, "manifest entries missing from lib: 0")
        assertContains(result, "lib has the server: true")
        val entries = Regex("manifest entries: (\\d+)").find(result.output)!!.groupValues[1].toInt()
        assertTrue(entries > 100, "the thin jar lists only $entries libraries")
        // F3: one flag per line, the form `java @jvm.args` reads.
        assertContains(result, "jvm.args: $mandatoryJvmFlags")
    }

    @Test
    fun `a Spring host that applies io_spring_dependency-management fails the build`() {
        val result = fixture("spring-host", "help", "-PapplyDependencyManagement").buildAndFail()

        assertContains(result, "applies io.spring.dependency-management. It must not")
    }

    @Test
    fun `validateConfig passes for a configuration directory that keeps the rules`() {
        val result = fixture("config-check", ":config:validateConfig").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":config:validateConfig")?.outcome)
        assertContains(result, "validateConfig: 4 settings, 2 application(s) [dhc-a, dhc-b], 3 environment(s) [dev, prod, uat].")
    }

    @Test
    fun `validateConfig fails on a key that one environment has and another has not`() {
        val runner = fixture("config-check", ":config:validateConfig")
        File(runner.projectDir, "config/env/uat.env").appendText("DHC_HTTP_HOST=0.0.0.0\n")

        val result = runner.buildAndFail()

        assertContains(result, "env/dev.env: DHC_HTTP_HOST is missing. It is set in uat.env")
        assertContains(result, "env/prod.env: DHC_HTTP_HOST is missing. It is set in uat.env")
    }

    @Test
    fun `validateConfig fails on a key that looks like a secret, without repeating its value`() {
        val runner = fixture("config-check", ":config:validateConfig")
        // Made up for this test; the point is that the task does not print it.
        File(runner.projectDir, "config/env/prod.env").appendText("DHC_DHC_A_AUTH_PSK=not-a-real-key-123\n")

        val result = runner.buildAndFail()

        assertContains(result, "env/prod.env line 5: DHC_DHC_A_AUTH_PSK looks like a secret")
        assertFalse(result.output.contains("not-a-real-key-123"), "the value of a secret-looking key must not be printed")
    }

    @Test
    fun `validateConfig reports every problem at once, each with its place`() {
        val runner = fixture("config-check", ":config:validateConfig")
        File(runner.projectDir, "config/apps/dhc-b.env").appendText("DHC_HTTP_PROT=10001\n")
        File(runner.projectDir, "config/apps/dhc-a.env").appendText("DHC_DHC_A_HTTP_HOST=localhost\n")
        File(runner.projectDir, "config/env/dev.env").appendText("DHC_DHC_C_HTTP_PORT=10002\nexport DHC_AUTH_MODE=psk\n")
        File(runner.projectDir, "config/env/uat.env").appendText("DHC_AUTH_MODE=\"psk\"\n")
        File(runner.projectDir, "config/env/prod.env").delete()

        val result = runner.buildAndFail()

        assertContains(result, "apps/dhc-b.env: DHC_HTTP_PROT is not a setting")
        assertContains(result, "apps/dhc-a.env: DHC_DHC_A_HTTP_HOST is scoped to the application dhc-a")
        assertContains(result, "env/dev.env: DHC_DHC_C_HTTP_PORT is not a setting")
        assertContains(result, "env/dev.env line 6: not KEY=value with a key named DHC_<NAME>: export DHC_AUTH_MODE=psk")
        assertContains(result, "env/uat.env line 5: the value of DHC_AUTH_MODE has quotes")
        assertContains(result, "env/prod.env does not exist")
    }

    private fun assertFlags(result: BuildResult, vararg tasks: String) {
        for (task in tasks) {
            val line = result.output.lineSequence().firstOrNull { it.startsWith("flags of $task: ") }
                ?: throw AssertionError("no 'flags of $task' line in:\n${result.output}")
            for (flag in mandatoryJvmFlags) {
                assertTrue(line.contains(flag), "$task is missing $flag (F3): $line")
            }
        }
    }

    private fun assertContains(result: BuildResult, expected: String) {
        assertTrue(result.output.contains(expected), "expected the build output to contain '$expected' but it was:\n${result.output}")
    }

    /** Copies a fixture build into the build directory and prepares a Gradle run of it. */
    private fun fixture(name: String, vararg arguments: String): GradleRunner {
        val source = File(System.getProperty("dhc.fixtures.dir"), name)
        val workDir = File(System.getProperty("dhc.fixtures.workDir"), name)
        workDir.deleteRecursively()
        source.copyRecursively(workDir)
        return GradleRunner.create()
            .withProjectDir(workDir)
            .withArguments(listOf(*arguments, "-PdhcRepoRoot=${System.getProperty("dhc.repoRoot")}", "--stacktrace"))
    }
}
