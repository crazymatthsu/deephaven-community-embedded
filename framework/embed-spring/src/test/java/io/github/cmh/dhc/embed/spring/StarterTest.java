package io.github.cmh.dhc.embed.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cmh.dhc.embed.EmbedSettings;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import io.github.cmh.dhc.embed.spring.host.TestHost;
import io.github.cmh.dhc.testkit.TableAssertions;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T4: a Spring Boot application with the starter on its classpath has a running Deephaven. One
 * context for the class, because Deephaven starts once per JVM (F10).
 */
@SpringBootTest(classes = TestHost.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dhc.app.id=spring-test",
                "dhc.app.modules=spring-test",
                "dhc.http.port=0",
                "dhc.auth.mode=anonymous",
                "dhc.tables.endpoint=true",
                "management.endpoint.health.show-details=always",
        })
class StarterTest {

    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;

    @DynamicPropertySource
    static void workDir(DynamicPropertyRegistry registry) throws IOException {
        String directory = Files.createTempDirectory("dhc-spring-test-").toString();
        registry.add("dhc.work.dir", () -> directory);
    }

    @LocalServerPort
    int springPort;

    @Autowired
    EmbeddedDeephaven deephaven;

    @Autowired
    EmbedSettings settings;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void deephavenRunsWithSettingsFromSpring() {
        assertTrue(deephaven.isRunning());
        assertEquals("spring-test", settings.appId());
        assertEquals(List.of("spring-test"), settings.appModules());
        assertEquals(EmbedSettings.AuthMode.ANONYMOUS, settings.authMode());
        assertTrue(deephaven.httpPort() > 0 && deephaven.httpPort() != springPort,
                "Deephaven and Spring MVC listen on two ports");
    }

    @Test
    void springKeepsWhatItOwns() {
        // SideEffectPolicy.hostKeepsControl(): Deephaven replaced neither stream.
        assertSame(OUT, System.out, "System.out was replaced");
        assertSame(ERR, System.err, "System.err was replaced");
        assertSame(settings, deephaven.settings(), "the instance was built from the settings bean");
    }

    @Test
    void aHostBindingBeanIsWhatTheScriptSees() {
        List<Map<String, Object>> rows = TableAssertions.rows(deephaven, deephaven.table("hello"));
        assertEquals(3, rows.size());
        assertEquals(Map.of("N", 1, "Text", "hello from a Spring bean"), rows.get(0));
    }

    @Test
    void healthReportsDeephaven() throws Exception {
        HttpResponse<String> response = Http.get("http://localhost:" + springPort + "/actuator/health");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode health = json.readTree(response.body());
        assertEquals("UP", health.path("status").asText(), response.body());
        JsonNode indicator = health.path("components").path("deephaven");
        assertEquals("UP", indicator.path("status").asText(), response.body());
        assertEquals("spring-test", indicator.path("details").path("appId").asText());
        assertEquals(deephaven.httpPort(), indicator.path("details").path("port").asInt());
        assertEquals("anonymous", indicator.path("details").path("authentication").asText());
    }

    @Test
    void theTableListingIsThereWhenAskedFor() throws Exception {
        HttpResponse<String> response = Http.get("http://localhost:" + springPort + "/dhc/tables");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode hello = null;
        for (JsonNode table : json.readTree(response.body())) {
            if ("hello".equals(table.path("name").asText())) {
                hello = table;
            }
        }
        assertTrue(hello != null, "no table 'hello' in " + response.body());
        assertEquals(3, hello.path("rows").asInt());
        assertEquals("[\"N\",\"Text\"]", hello.path("columns").toString());
    }

    @Test
    void deephavenServesItsWebUiOnItsOwnPort() throws Exception {
        assertEquals(200, Http.get("http://localhost:" + deephaven.httpPort() + "/ide/").statusCode());
        assertEquals(200, Http.get("http://localhost:" + deephaven.httpPort() + "/jsapi/dh-core.js").statusCode());
        // Nothing of the application is on that port.
        assertEquals(404, Http.get("http://localhost:" + deephaven.httpPort() + "/actuator/health").statusCode());
    }
}
