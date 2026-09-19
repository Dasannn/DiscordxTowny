package com.discordtowny.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP transport seam decoupling {@link DefaultUpdateService} from JDK HTTP details and real network.
 */
public interface HttpTransport {

    /**
     * Executes an HTTP GET request.
     *
     * @param uri destination URI
     * @param headers request headers
     * @param timeout request timeout
     * @return HTTP response
     * @throws IOException on network or connection errors
     * @throws InterruptedException if interrupted
     */
    HttpResponse executeGet(URI uri, Map<String, String> headers, Duration timeout) throws IOException, InterruptedException;

    record HttpResponse(int statusCode, Map<String, String> headers, InputStream body) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }

        public String getHeader(String name) {
            if (headers == null || name == null) {
                return null;
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
