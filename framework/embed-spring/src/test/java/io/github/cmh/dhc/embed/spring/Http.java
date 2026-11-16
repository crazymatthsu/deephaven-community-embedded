package io.github.cmh.dhc.embed.spring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One GET, for the tests. */
final class Http {

    private Http() {}

    /**
     * A client per request, closed afterwards: no connection stays open to a server the test is
     * about to stop, and no client thread is left for the test to mistake for a leak.
     */
    static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
