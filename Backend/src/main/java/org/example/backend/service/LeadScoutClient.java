package org.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends a lead to LeadScout. {@code app.leadscout.mode} decides how far it goes:
 *
 * <ul>
 *   <li>{@code fake} (the default): nothing leaves this server. The answer comes
 *       from the start of the email, so every case can be tried, like
 *       {@code exists@example.com}.</li>
 *   <li>{@code test}: LeadScout itself, with {@code test: true} and the test email
 *       and phone its documentation asks for, so no real person's details leave.</li>
 *   <li>{@code live}: the real thing.</li>
 * </ul>
 */
@Service
public class LeadScoutClient {

    private static final Logger log = LoggerFactory.getLogger(LeadScoutClient.class);

    public static final String FAKE = "fake";
    public static final String TEST = "test";
    public static final String LIVE = "live";

    private static final String TEST_EMAIL = "test@test.com";
    private static final String TEST_PHONE = "(555) 555-5555";
    private static final String FAKE_LINK = "https://example.com/fake-offer";

    /**
     * What came back.
     *
     * @param status   LeadScout's status in capitals; "" when it gave none, null when there was no answer
     * @param tryAgain whether one more send is safe. Not after a timeout: LeadScout may
     *                 have taken the lead, and a second send would come back as EXISTS.
     */
    public record Answer(Integer httpStatus, String status, String redirectUrl, String body,
                         String problem, boolean tryAgain, int durationMs) {
    }

    private final String mode;
    private final URI url;
    private final String apiKey;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public LeadScoutClient(@Value("${app.leadscout.mode:fake}") String mode,
                           @Value("${app.leadscout.url:https://app.leadscout.ca/api/v1/public/submit}") String url,
                           @Value("${app.leadscout.api-key:}") String apiKey,
                           @Value("${app.leadscout.read-timeout-seconds:20}") long readTimeoutSeconds,
                           ObjectMapper objectMapper) {
        // A typo must stop the server, not quietly send real people's details somewhere.
        if (!Set.of(FAKE, TEST, LIVE).contains(mode)) {
            throw new IllegalStateException("app.leadscout.mode must be fake, test or live, not: " + mode);
        }
        this.mode = mode;
        this.url = URI.create(url);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        log.info("LeadScout mode: {}", mode);
    }

    public String mode() {
        return mode;
    }

    /** The body for LeadScout: everything but live is marked as a test, and test mode hides the person. */
    public Map<String, Object> body(Map<String, Object> data, Map<String, String> extra,
                                    String language, String ip, String userAgent) {
        Map<String, Object> sent = new LinkedHashMap<>(data);
        if (TEST.equals(mode)) {
            sent.put("email", TEST_EMAIL);
            sent.put("phone", TEST_PHONE);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", sent);
        body.put("extra", extra);
        body.put("type", "PERSONAL");
        body.put("language", language);
        body.put("ipAddress", ip);
        body.put("userAgent", userAgent);
        body.put("test", !LIVE.equals(mode));
        return body;
    }

    /** @param attempt 1 for the first send, 2 for the one retry */
    public Answer send(Map<String, Object> body, int attempt) {
        return FAKE.equals(mode) ? fakeAnswer(body, attempt) : realAnswer(body);
    }

    private Answer realAnswer(Map<String, Object> body) {
        if (apiKey.isEmpty()) {
            log.error("LeadScout mode is {} but no API key is set (LEADSCOUT_API_KEY)", mode);
            return new Answer(null, null, null, null, "No LeadScout API key is set.", false, 0);
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return read(response.statusCode(), response.body(), millisSince(started));
        } catch (HttpConnectTimeoutException e) {
            // Never reached LeadScout, so nothing was taken and one more try is safe.
            return new Answer(null, null, null, null, "Could not reach LeadScout.", true, millisSince(started));
        } catch (HttpTimeoutException e) {
            return new Answer(null, null, null, null, "LeadScout took too long to answer.", false, millisSince(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(null, null, null, null, "Stopped while waiting for LeadScout.", false, millisSince(started));
        } catch (Exception e) {
            log.warn("Could not reach LeadScout: {}", e.toString());
            return new Answer(null, null, null, null, "Could not reach LeadScout.", true, millisSince(started));
        }
    }

    private Answer read(int httpStatus, String text, int duration) {
        String status = "";
        String redirectUrl = null;
        String message = null;
        try {
            JsonNode json = objectMapper.readTree(text);
            status = json.path("status").asString("").trim().toUpperCase(Locale.ROOT);
            redirectUrl = json.path("redirectUrl").asString(null);
            message = json.path("message").asString(null);
        } catch (Exception e) {
            log.warn("LeadScout answered with something that is not JSON (HTTP {})", httpStatus);
        }
        boolean understood = !status.isEmpty() && !"ERROR".equals(status);
        String problem = understood ? null
                : message != null ? message
                : "LeadScout gave no status (HTTP " + httpStatus + ").";
        return new Answer(httpStatus, status, redirectUrl, text, problem, !understood, duration);
    }

    /** For trying every case with no key and no network. "flaky" fails once, then works. */
    private Answer fakeAnswer(Map<String, Object> body, int attempt) {
        Object data = body.get("data");
        String email = data instanceof Map<?, ?> map ? String.valueOf(map.get("email")) : "";
        String name = email.toLowerCase(Locale.ROOT);
        if (name.startsWith("timeout")) {
            return new Answer(null, null, null, null, "LeadScout took too long to answer.", false, 0);
        }
        if (name.startsWith("down")) {
            return new Answer(null, null, null, null, "Could not reach LeadScout.", true, 0);
        }
        if (name.startsWith("flaky") && attempt == 1) {
            return new Answer(null, null, null, null, "Could not reach LeadScout.", true, 0);
        }
        String status = name.startsWith("cpf") ? "CPF"
                : name.startsWith("notsold") ? "NOT_SOLD"
                : name.startsWith("exists") ? "EXISTS"
                : name.startsWith("error") ? "ERROR"
                : name.startsWith("empty") ? ""
                : "SOLD";
        boolean hasLink = Set.of("SOLD", "CPF", "NOT_SOLD").contains(status) && !name.startsWith("nolink");
        String link = hasLink ? ",\"redirectUrl\":\"" + FAKE_LINK + "\"" : "";
        return read(200, "{\"fake\":true,\"status\":\"" + status + "\"" + link + "}", 0);
    }

    private static int millisSince(long startedNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
