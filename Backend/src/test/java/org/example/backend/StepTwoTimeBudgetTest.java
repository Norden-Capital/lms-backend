package org.example.backend;

import org.example.backend.entity.Brand;
import org.example.backend.repository.BrandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One step 2 waits about the read timeout in all (owner, 2026-11-20), so the form's
 * server always gets its answer. A short timeout (16 s) keeps the test quick:
 * a retry needs 10 s left, after 5 s kept back for reaching LeadScout.
 */
@SpringBootTest(properties = {"app.leadscout.mode=fake", "app.leadscout.read-timeout-seconds=16"})
@AutoConfigureMockMvc
class StepTwoTimeBudgetTest {

    private static final String DOMAIN = "budgetbrand.ca";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BrandRepository brands;

    @BeforeEach
    void setUp() {
        if (brands.findByDomain(DOMAIN).isEmpty()) {
            brands.save(Brand.builder().publicId("BR-BUDG-TEST").name(DOMAIN).email("support@" + DOMAIN)
                    .domain(DOMAIN).active(true).build());
        }
    }

    private String apply(String start) throws Exception {
        String address = "10.7.0." + NEXT_ADDRESS.getAndIncrement();
        Map<String, Object> form = ApplyTestSupport.stepOne("apply." + DOMAIN, start + "-" + UUID.randomUUID() + "@example.com");
        String token = json.readTree(mvc.perform(post("/api/public/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET).header("X-Client-IP", address)
                        .content(json.writeValueAsString(form)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("stepToken").asString();
        mvc.perform(post("/api/public/applications/" + token + "/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET).header("X-Client-IP", address)
                        .content(json.writeValueAsString(ApplyTestSupport.stepTwo())))
                .andExpect(status().isOk());
        return token;
    }

    private int sendsOf(String token) {
        return db.queryForObject("""
                SELECT count(*) FROM application_events e JOIN loan_applications a ON a.id = e.application_id
                WHERE a.step_token = ?""", Integer.class, token);
    }

    private String statusOf(String token) {
        return db.queryForObject("SELECT status FROM loan_applications WHERE step_token = ?", String.class, token);
    }

    @Test
    void aQuickFailureIsTriedOnceMore() throws Exception {
        String token = apply("flaky");
        assertThat(sendsOf(token)).isEqualTo(2);
        assertThat(statusOf(token)).isEqualTo("LEAD");
    }

    @Test
    void aLateFailureIsNotTriedAgainSoTheWholeStepStaysInTime() throws Exception {
        long started = System.nanoTime();
        // LeadScout answers ERROR after 4 s: 16 - 4 - 5 = 7 s left, under 10, so no retry.
        String token = apply("wait4-error");
        long seconds = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();

        assertThat(sendsOf(token)).isEqualTo(1);
        assertThat(statusOf(token)).isEqualTo("FAILED");
        assertThat(seconds).isLessThan(16);
    }

    @Test
    void aTimeoutEndsAtTheLimitAndIsNeverSentAgain() throws Exception {
        long started = System.nanoTime();
        String token = apply("wait30-sold");
        long seconds = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();

        assertThat(sendsOf(token)).isEqualTo(1);
        assertThat(statusOf(token)).isEqualTo("FAILED");
        assertThat(seconds).isBetween(15L, 20L);
    }
}
