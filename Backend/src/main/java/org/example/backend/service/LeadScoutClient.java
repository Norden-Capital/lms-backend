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
 *       {@code exists@example.com} ("existslink@…" answers EXISTS with a link,
 *       "ibv@…" answers IBV_PRE_START with a link).</li>
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
    /** LeadScout's docs: "Please use (514) 555-5555 for production testing." 555 is not a real area code. */
    private static final String TEST_PHONE = "(514) 555-5555";
    /** The only debt amounts LeadScout takes. Changed 2026-11-09. */
    private static final int[] DEBT_AMOUNTS = {0, 2500, 7500, 10000, 15000, 20000};
    private static final String FAKE_LINK = "https://example.com/fake-offer";
    private static final String TOO_LONG = "LeadScout took too long to answer.";
    /** The fake waits at most this long ("wait40-…"), so a typo can't hold a thread for hours. */
    private static final int FAKE_WAIT_MAX_SECONDS = 120;

    /**
     * What came back.
     *
     * @param status   LeadScout's status in capitals; "" when it gave none, null when there was no answer
     * @param tryAgain whether one more send is safe. Not after a timeout: LeadScout may
     *                 have taken the lead, and a second send would come back as EXISTS.
     */
    public record Answer(Integer httpStatus, String status, String redirectUrl, String body,
                         String problem, boolean tryAgain, int durationMs) {

        /** LeadScout did not answer in time (read timeout). Never sent again. */
        public boolean timedOut() {
            return httpStatus == null && TOO_LONG.equals(problem);
        }
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
                           @Value("${app.leadscout.read-timeout-seconds:45}") long readTimeoutSeconds,
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
        log.info("LeadScout mode: {}, waits up to {}s for an answer", mode, readTimeoutSeconds);
    }

    public String mode() {
        return mode;
    }

    /** How long a send waits for LeadScout's answer. Owner, 2026-11-20: 45 seconds (was 20). */
    public Duration readTimeout() {
        return readTimeout;
    }

    /** The body for LeadScout: everything but live is marked as a test, and test mode hides the person. */
    public Map<String, Object> body(Map<String, Object> data, Map<String, String> extra,
                                    String language, String ip, String userAgent) {
        Map<String, Object> sent = new LinkedHashMap<>(data);
        // The person types any amount; LeadScout takes only a few. The LMS keeps what they typed.
        if (sent.get("unsecuredDebtAmount") instanceof Number debt) {
            sent.put("unsecuredDebtAmount", nearestDebtAmount(debt.intValue()));
        }
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
        return send(body, attempt, readTimeout);
    }

    /** @param timeout how long this one send may wait for the answer (a retry gets only what is left) */
    public Answer send(Map<String, Object> body, int attempt, Duration timeout) {
        return FAKE.equals(mode) ? fakeAnswer(body, attempt, timeout) : realAnswer(body, timeout);
    }

    private Answer realAnswer(Map<String, Object> body, Duration timeout) {
        if (apiKey.isEmpty()) {
            log.error("LeadScout mode is {} but no API key is set (LEADSCOUT_API_KEY)", mode);
            return new Answer(null, null, null, null, "No LeadScout API key is set.", false, 0);
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(timeout)
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
            return new Answer(null, null, null, null, TOO_LONG, false, millisSince(started));
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

    /**
     * For trying every case with no key and no network. "flaky" fails once, then works.
     * "wait40-sold@…" answers after 40 seconds, like a slow LeadScout (2026-11-20); a
     * wait at or over the read timeout ends as a timeout, after the timeout. Fake mode only.
     */
    private Answer fakeAnswer(Map<String, Object> body, int attempt, Duration timeout) {
        Object data = body.get("data");
        String email = data instanceof Map<?, ?> map ? String.valueOf(map.get("email")) : "";
        String name = email.toLowerCase(Locale.ROOT);
        long started = System.nanoTime();
        java.util.regex.Matcher wait = java.util.regex.Pattern.compile("^wait([0-9]{1,3})-(.*)$").matcher(name);
        if (wait.matches()) {
            long seconds = Math.min(Integer.parseInt(wait.group(1)), FAKE_WAIT_MAX_SECONDS);
            name = wait.group(2);
            boolean tooLong = seconds * 1000 >= timeout.toMillis();
            try {
                Thread.sleep(tooLong ? timeout.toMillis() : seconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Answer(null, null, null, null, "Stopped while waiting for LeadScout.", false, millisSince(started));
            }
            if (tooLong) return new Answer(null, null, null, null, TOO_LONG, false, millisSince(started));
        }
        if (name.startsWith("timeout")) {
            return new Answer(null, null, null, null, TOO_LONG, false, 0);
        }
        if (name.startsWith("down")) {
            return new Answer(null, null, null, null, "Could not reach LeadScout.", true, 0);
        }
        if (name.startsWith("flaky") && attempt == 1) {
            return new Answer(null, null, null, null, "Could not reach LeadScout.", true, 0);
        }
        String status = name.startsWith("cpf") ? "CPF"
                : name.startsWith("notsold") ? "NOT_SOLD"
                : name.startsWith("ibv") ? "IBV_PRE_START"
                : name.startsWith("exists") ? "EXISTS"
                : name.startsWith("error") ? "ERROR"
                : name.startsWith("empty") ? ""
                : "SOLD";
        boolean hasLink = (Set.of("SOLD", "CPF", "NOT_SOLD", "IBV_PRE_START").contains(status) || name.startsWith("existslink"))
                && !name.startsWith("nolink");
        String link = hasLink ? ",\"redirectUrl\":\"" + FAKE_LINK + "\"" : "";
        return read(200, "{\"fake\":true,\"status\":\"" + status + "\"" + link + "}", millisSince(started));
    }

    /** The closest amount LeadScout takes; a tie goes up. 4,000 becomes 2,500 and 5,000 becomes 7,500. */
    public static int nearestDebtAmount(int debt) {
        int best = DEBT_AMOUNTS[0];
        for (int amount : DEBT_AMOUNTS) {
            if (Math.abs(amount - debt) <= Math.abs(best - debt)) best = amount;
        }
        return best;
    }

    private static int millisSince(long startedNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
