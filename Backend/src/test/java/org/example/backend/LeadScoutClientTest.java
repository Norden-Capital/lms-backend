package org.example.backend;

import com.sun.net.httpserver.HttpServer;
import org.example.backend.service.LeadScoutClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real sending code against a small local server standing in for LeadScout. */
class LeadScoutClientTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private int answerCode = 200;
    private String answer = "{\"status\":\"sold\",\"redirectUrl\":\"https://offer.example/1\"}";
    private long delayMs = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answerCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LeadScoutClient client(String mode, String key, long timeoutSeconds) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/submit";
        return new LeadScoutClient(mode, url, key, timeoutSeconds, json);
    }

    private static Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", "real.person@example.com");
        data.put("phone", "(514) 555-1234");
        data.put("firstName", "Real");
        return data;
    }

    private Map<String, Object> body(LeadScoutClient client) {
        return client.body(data(), Map.of("aff_utm_source", "google"), "fr", "1.2.3.4", "Test Browser");
    }

    @Test
    void testModeHidesThePersonAndMarksItAsATest() {
        LeadScoutClient client = client("test", "my-key", 5);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        JsonNode sent = json.readTree(received.getFirst());
        assertThat(sent.path("test").asBoolean()).isTrue();
        assertThat(sent.path("data").path("email").asString()).isEqualTo("test@test.com");
        assertThat(sent.path("data").path("phone").asString()).isEqualTo("(555) 555-5555");
        assertThat(sent.path("type").asString()).isEqualTo("PERSONAL");
        assertThat(sent.path("language").asString()).isEqualTo("fr");
        assertThat(sent.path("ipAddress").asString()).isEqualTo("1.2.3.4");
        assertThat(sent.path("userAgent").asString()).isEqualTo("Test Browser");
        assertThat(sent.path("extra").path("aff_utm_source").asString()).isEqualTo("google");
        assertThat(keys.getFirst()).isEqualTo("my-key");

        assertThat(result.status()).isEqualTo("SOLD");
        assertThat(result.redirectUrl()).isEqualTo("https://offer.example/1");
        assertThat(result.tryAgain()).isFalse();
    }

    @Test
    void liveModeSendsTheRealPerson() {
        LeadScoutClient client = client("live", "my-key", 5);

        client.send(body(client), 1);

        JsonNode sent = json.readTree(received.getFirst());
        assertThat(sent.path("test").asBoolean()).isFalse();
        assertThat(sent.path("data").path("email").asString()).isEqualTo("real.person@example.com");
    }

    @Test
    void fakeModeNeverCallsAnybody() {
        LeadScoutClient client = client("fake", "", 5);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        assertThat(received).isEmpty();
        assertThat(result.status()).isEqualTo("SOLD");
    }

    @Test
    void aMistypedModeStopsTheServer() {
        assertThatThrownBy(() -> client("lve", "my-key", 5)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noKeyMeansNothingIsSent() {
        LeadScoutClient client = client("live", "  ", 5);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        assertThat(received).isEmpty();
        assertThat(result.status()).isNull();
        assertThat(result.problem()).isNotBlank();
    }

    @Test
    void anErrorAnswerMayBeTriedOnceMore() {
        answer = "{\"status\":\"ERROR\",\"message\":\"Bad field\"}";
        answerCode = 400;
        LeadScoutClient client = client("test", "my-key", 5);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.problem()).isEqualTo("Bad field");
        assertThat(result.tryAgain()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(400);
    }

    @Test
    void anAnswerThatIsNotJsonCountsAsNoAnswer() {
        answer = "<html>Bad gateway</html>";
        answerCode = 502;
        LeadScoutClient client = client("test", "my-key", 5);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        assertThat(result.status()).isEmpty();
        assertThat(result.tryAgain()).isTrue();
    }

    @Test
    void aTimeoutIsNotSentAgainBecauseLeadScoutMayHaveTakenIt() {
        delayMs = 3000;
        LeadScoutClient client = client("test", "my-key", 1);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        assertThat(result.status()).isNull();
        assertThat(result.tryAgain()).isFalse();
    }

    @Test
    void aServerThatCannotBeReachedMayBeTriedOnceMore() {
        LeadScoutClient client = client("test", "my-key", 5);
        server.stop(0);

        LeadScoutClient.Answer result = client.send(body(client), 1);

        assertThat(result.status()).isNull();
        assertThat(result.tryAgain()).isTrue();
    }
}
