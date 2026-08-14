package com.playcity.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

interface BridgeRelayHttpTransport {
    Response post(URI uri, Map<String, String> headers, String body, int timeoutMillis, int maxResponseBytes)
        throws IOException, InterruptedException;

    record Response(int status, String body) {
    }

    final class JavaNet implements BridgeRelayHttpTransport {
        private final HttpClient client;

        JavaNet(int connectTimeoutMillis) {
            client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        }

        @Override
        public Response post(URI uri, Map<String, String> headers, String body, int timeoutMillis, int maxResponseBytes)
            throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMillis))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(request::header);
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxResponseBytes) {
                        throw new IOException("relay_response_too_large");
                    }
                    output.write(buffer, 0, read);
                }
                return new Response(response.statusCode(), output.toString(StandardCharsets.UTF_8));
            }
        }
    }
}
