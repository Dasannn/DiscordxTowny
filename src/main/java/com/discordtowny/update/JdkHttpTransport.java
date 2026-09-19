package com.discordtowny.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Production {@link HttpTransport} backed by JDK's {@link HttpClient}.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Official source destination policy on initial URI and all redirect hops (F1).</li>
 *   <li>Bounded redirect count and overall operation timeout (F1, F5).</li>
 * </ul>
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final int MAX_REDIRECTS = 5;
    private final HttpClient httpClient;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public JdkHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
    }

    @Override
    public HttpResponse executeGet(URI uri, Map<String, String> headers, Duration timeout) throws IOException, InterruptedException {
        UpdateSourcePolicy.validateDestination(uri);

        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(15);
        Instant deadline = Instant.now().plus(effectiveTimeout);

        URI currentUri = uri;
        int redirectCount = 0;

        while (true) {
            UpdateSourcePolicy.validateDestination(currentUri);

            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new HttpConnectTimeoutException("HTTP request timed out before contacting " + currentUri);
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(currentUri)
                    .GET()
                    .timeout(remaining);

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            java.net.http.HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream()
            );

            // In case a custom HttpClient followed redirects internally, validate final URI
            UpdateSourcePolicy.validateDestination(response.uri());

            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null && !location.isBlank()) {
                    response.body().close();
                    if (++redirectCount > MAX_REDIRECTS) {
                        throw new IOException("Too many redirects (limit " + MAX_REDIRECTS + ") while contacting " + uri);
                    }
                    URI nextUri = currentUri.resolve(location);
                    UpdateSourcePolicy.validateDestination(nextUri);
                    currentUri = nextUri;
                    continue;
                }
            }

            Map<String, String> responseHeaders = new HashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    responseHeaders.put(k, v.getFirst());
                }
            });

            return new HttpResponse(response.statusCode(), responseHeaders, response.body());
        }
    }
}
