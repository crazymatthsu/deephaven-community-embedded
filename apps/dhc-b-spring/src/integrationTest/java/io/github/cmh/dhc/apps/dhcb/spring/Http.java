package io.github.cmh.dhc.apps.dhcb.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** GET requests for the tests, as any HTTP client outside the application would make them. */
final class Http {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Http() {}

    /** A client per request, closed afterwards, so no connection stays open to a server a test stops. */
    static HttpResponse<String> get(String url) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("GET " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted during GET " + url);
        }
    }

    static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new AssertionError("Not JSON (status " + response.statusCode() + "): " + response.body(), e);
        }
    }
}
