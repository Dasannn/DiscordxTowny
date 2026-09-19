package com.discordtowny.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Production {@link HttpTransport} backed by JDK's {@link HttpClient}.
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient httpClient;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public JdkHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
    }

    @Override
    public HttpResponse executeGet(URI uri, Map<String, String> headers, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(timeout != null ? timeout : Duration.ofSeconds(15));

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        java.net.http.HttpResponse<InputStream> response = httpClient.send(
                builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream()
        );

        Map<String, String> responseHeaders = new HashMap<>();
        response.headers().map().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                responseHeaders.put(k, v.getFirst());
            }
        });

        return new HttpResponse(response.statusCode(), responseHeaders, response.body());
    }
}
