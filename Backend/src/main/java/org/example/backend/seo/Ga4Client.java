package org.example.backend.seo;

import org.example.backend.exception.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Google Analytics Data API (v1beta), one call: {@code batchRunReports}, which
 * answers up to five reports for one property at once.
 */
@Component
public class Ga4Client {

    private static final Logger log = LoggerFactory.getLogger(Ga4Client.class);
    private static final String API = "https://analyticsdata.googleapis.com/v1beta/properties/";

    private final GoogleServiceAccount account;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Ga4Client(GoogleServiceAccount account, ObjectMapper json) {
        this.account = account;
        this.json = json;
    }

    ObjectMapper json() {
        return json;
    }

    /** One report per request, in the same order. */
    List<JsonNode> batch(String propertyId, List<ObjectNode> requests) {
        ObjectNode body = json.createObjectNode();
        ArrayNode list = body.putArray("requests");
        requests.forEach(list::add);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(API + propertyId + ":batchRunReports"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Analytics said no to property " + propertyId
                    + ". Add " + account.clientEmail() + " as a Viewer in that property.");
            }
            if (response.statusCode() == 400 || response.statusCode() == 404) {
                log.warn("GA4 refused property {}: {}", propertyId, response.body());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Analytics has no property " + propertyId + ". Check the number on Brands.");
            }
            if (response.statusCode() == 429) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Analytics asks us to wait. Try again later.");
            }
            if (response.statusCode() != 200) {
                log.warn("GA4 answered {}: {}", response.statusCode(), response.body());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Analytics did not answer. Try again soon.");
            }
            JsonNode reports = json.readTree(response.body()).path("reports");
            return reports.isArray() ? List.copyOf(reports.valueStream().toList()) : List.of();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Analytics did not answer. Try again soon.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Analytics did not answer. Try again soon.");
        }
    }
}
