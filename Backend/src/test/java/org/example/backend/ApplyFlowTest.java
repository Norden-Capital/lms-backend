package org.example.backend;

import org.example.backend.entity.Brand;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.BrandRepository;
import org.example.backend.service.BrandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The two steps from the outside, on a real database, against the fake LeadScout. */
@SpringBootTest(properties = "app.leadscout.mode=fake")
@AutoConfigureMockMvc
class ApplyFlowTest {

    private static final String HOST = "apply.testbrand.ca";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BrandRepository brands;
    @Autowired BrandService brandService;

    /** Rate limits count per address, so each test is its own visitor. */
    private String address;

    @BeforeEach
    void setUp() {
        address = "10.1.0." + NEXT_ADDRESS.getAndIncrement();
        if (brands.findByDomain("testbrand.ca").isEmpty()) {
            brands.save(brand("testbrand.ca", true));
            brands.save(brand("offbrand.ca", false));
        }
    }

    private static Brand brand(String domain, boolean active) {
        return Brand.builder().publicId("BR-" + domain.substring(0, 4).toUpperCase() + "-TEST")
                .name(domain).email("support@" + domain).domain(domain).active(active).build();
    }

    private static String email(String start) {
        return start + "-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions postStepOne(Map<String, Object> form) throws Exception {
        return mvc.perform(post("/api/public/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                .header("X-Client-IP", address)
                .content(json.writeValueAsString(form)));
    }

    private ResultActions postStepTwo(String token, Map<String, Object> form) throws Exception {
        return mvc.perform(post("/api/public/applications/" + token + "/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                .header("X-Client-IP", address)
                .content(json.writeValueAsString(form)));
    }

    private String startApplication(String email) throws Exception {
        String body = postStepOne(ApplyTestSupport.stepOne(HOST, email))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("stepToken").asString();
    }

    private JsonNode finish(String token) throws Exception {
        String body = postStepTwo(token, ApplyTestSupport.stepTwo())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String statusOf(String token) {
        return db.queryForObject("SELECT status FROM loan_applications WHERE step_token = ?", String.class, token);
    }

    private int sendsOf(String token) {
        return db.queryForObject("""
                SELECT count(*) FROM application_events e JOIN loan_applications a ON a.id = e.application_id
                WHERE a.step_token = ?""", Integer.class, token);
    }

    private int count(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args);
    }

    @Test
    void stepOneStoresThePersonAsAbandoned() throws Exception {
        String email = email("sold");
        String token = startApplication(email);

        assertThat(token).hasSizeGreaterThan(40);
        assertThat(statusOf(token)).isEqualTo("ABANDONED");
        assertThat(sendsOf(token)).isZero();
        assertThat(count("SELECT count(*) FROM clients WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void theAnswersAreNotReadableInTheDatabase() throws Exception {
        String token = startApplication(email("sold"));
        finish(token);

        String answers = db.queryForObject("SELECT answers FROM loan_applications WHERE step_token = ?", String.class, token);
        String request = db.queryForObject("""
                SELECT e.request FROM application_events e JOIN loan_applications a ON a.id = e.application_id
                WHERE a.step_token = ?""", String.class, token);
        assertThat(answers).doesNotContain("Secretname", "1990-03-15", "2200");
        assertThat(request).doesNotContain("Secretname", "1990-03-15", "2200");
    }

    @ParameterizedTest
    @CsvSource({
            // email start, status in LMS, what the form shows, sends
            "sold,    LEAD,   GO_TO_LINK,      1",
            "cpf,     LEAD,   GO_TO_LINK,      1",
            "notsold, LEAD,   GO_TO_LINK,      1",
            "exists,  LEAD,   ALREADY_APPLIED, 1",
            "error,   FAILED, TRY_LATER,       2",
            "empty,   FAILED, TRY_LATER,       2",
            "down,    FAILED, TRY_LATER,       2",
            "timeout, FAILED, TRY_LATER,       1",
            "nolink,  FAILED, TRY_LATER,       1",
            "flaky,   LEAD,   GO_TO_LINK,      2",
    })
    void everyLeadScoutAnswerEndsInTheRightPlace(String start, String lmsStatus, String outcome, int sends) throws Exception {
        String token = startApplication(email(start));

        JsonNode result = finish(token);

        assertThat(statusOf(token)).isEqualTo(lmsStatus);
        assertThat(result.path("outcome").asString()).isEqualTo(outcome);
        assertThat(sendsOf(token)).isEqualTo(sends);
        if ("GO_TO_LINK".equals(outcome)) {
            assertThat(result.path("redirectUrl").asString()).startsWith("https://");
        } else {
            assertThat(result.path("redirectUrl").isNull()).isTrue();
        }
    }

    @Test
    void aSecondClickDoesNotSendAgain() throws Exception {
        String token = startApplication(email("sold"));
        JsonNode first = finish(token);
        JsonNode second = finish(token);

        assertThat(second).isEqualTo(first);
        assertThat(sendsOf(token)).isEqualTo(1);
    }

    @Test
    void aFailedApplicationCanBeTriedAgain() throws Exception {
        String token = startApplication(email("error"));
        finish(token);
        assertThat(statusOf(token)).isEqualTo("FAILED");
        finish(token);
        assertThat(sendsOf(token)).isEqualTo(4);
    }

    @Test
    void onePersonIsOneRowWithAnApplicationEachTime() throws Exception {
        String email = email("sold");
        String first = startApplication(email);
        String second = startApplication(email.toUpperCase());

        assertThat(first).isNotEqualTo(second);
        assertThat(count("SELECT count(*) FROM clients WHERE email = ?", email)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM loan_applications a JOIN clients c ON c.id = a.client_id
                WHERE c.email = ?""", email)).isEqualTo(2);
    }

    @Test
    void becomingALeadTakesOldTriesOutOfAbandoned() throws Exception {
        String email = email("sold");
        String oldTry = startApplication(email);
        String newTry = startApplication(email);

        finish(newTry);

        assertThat(statusOf(oldTry)).isEqualTo("RETURNED");
        assertThat(statusOf(newTry)).isEqualTo("LEAD");
    }

    @Test
    void pressingPreviousUpdatesTheSameApplication() throws Exception {
        String token = startApplication(email("sold"));
        String newEmail = email("sold");
        Map<String, Object> corrected = ApplyTestSupport.stepOne(HOST, newEmail);
        corrected.put("stepToken", token);

        int clientsBefore = count("SELECT count(*) FROM clients");
        String body = postStepOne(corrected).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).path("stepToken").asString()).isEqualTo(token);
        // The mistyped email's row went with it: same number of people as before.
        assertThat(count("SELECT count(*) FROM clients")).isEqualTo(clientsBefore);
        assertThat(count("""
                SELECT count(*) FROM loan_applications a JOIN clients c ON c.id = a.client_id
                WHERE a.step_token = ? AND c.email = ?""", token, newEmail)).isEqualTo(1);
    }

    @Test
    void afterFinishingAStepOneTokenStartsANewApplication() throws Exception {
        String email = email("sold");
        String token = startApplication(email);
        finish(token);
        Map<String, Object> again = ApplyTestSupport.stepOne(HOST, email);
        again.put("stepToken", token);

        String body = postStepOne(again).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).path("stepToken").asString()).isNotEqualTo(token);
        assertThat(statusOf(token)).isEqualTo("LEAD");
    }

    @Test
    void theSubdomainFindsTheBrand() throws Exception {
        String token = startApplication(email("sold"));
        assertThat(count("""
                SELECT count(*) FROM loan_applications a JOIN brands b ON b.id = a.brand_id
                WHERE a.step_token = ? AND b.domain = 'testbrand.ca'""", token)).isEqualTo(1);
    }

    @Test
    void unknownAndSwitchedOffBrandsAreRefused() throws Exception {
        postStepOne(ApplyTestSupport.stepOne("apply.nobody.ca", email("sold"))).andExpect(status().isBadRequest());
        postStepOne(ApplyTestSupport.stepOne("apply.offbrand.ca", email("sold"))).andExpect(status().isBadRequest());
    }

    @Test
    void aBrandWithApplicationsCannotBeRemoved() throws Exception {
        startApplication(email("sold"));
        String id = brands.findByDomain("testbrand.ca").orElseThrow().getPublicId();
        assertThatThrownBy(() -> brandService.delete(id, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void onlyTheFormsServerMayCall() throws Exception {
        String body = json.writeValueAsString(ApplyTestSupport.stepOne(HOST, email("sold")));
        mvc.perform(post("/api/public/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/applications").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", "wrong").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/applications").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownTokenIsNotFound() throws Exception {
        postStepTwo("no-such-token", ApplyTestSupport.stepTwo()).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource({
            "requestedAmount, 299",
            "requestedAmount, 3001",
            "email, not-an-email",
            "phone, 5145551234",
            "postalCode, H3E1C2",
            "province, ZZ",
            "language, es",
            "consent, false",
            "terms, false",
    })
    void badStepOneAnswersAreRefused(String field, String value) throws Exception {
        Map<String, Object> form = ApplyTestSupport.stepOne(HOST, email("sold"));
        form.put(field, value);
        postStepOne(form).andExpect(status().isBadRequest());
    }

    @Test
    void under18IsRefusedToTheDay() throws Exception {
        Map<String, Object> form = ApplyTestSupport.stepOne(HOST, email("sold"));
        form.put("birthDate", LocalDate.now().minusYears(18).plusDays(1).toString());
        postStepOne(form).andExpect(status().isBadRequest());

        form.put("birthDate", LocalDate.now().minusYears(18).toString());
        postStepOne(form).andExpect(status().isOk());
    }

    @Test
    void workersAndOwnersMustGiveTheExtraAnswers() throws Exception {
        String token = startApplication(email("sold"));

        Map<String, Object> worker = ApplyTestSupport.stepTwo();
        worker.put("incomeType", "full_time");
        postStepTwo(token, worker).andExpect(status().isBadRequest());

        Map<String, Object> owner = ApplyTestSupport.stepTwo();
        owner.put("ownHome", "yes");
        postStepTwo(token, owner).andExpect(status().isBadRequest());

        Map<String, Object> badTime = ApplyTestSupport.stepTwo();
        badTime.put("timeAtAddress", 7);
        postStepTwo(token, badTime).andExpect(status().isBadRequest());

        assertThat(statusOf(token)).isEqualTo("ABANDONED");

        worker.put("jobTitle", "Driver");
        worker.put("employerName", "Acme");
        worker.put("employedMonths", 12);
        worker.put("payFrequency", "BIWEEKLY");
        worker.put("payDate1", "2026-12-01");
        postStepTwo(token, worker).andExpect(status().isOk());
        assertThat(statusOf(token)).isEqualTo("LEAD");
    }

    @Test
    void tooManyTriesFromOneAddressAreStopped() throws Exception {
        Map<String, Object> bad = ApplyTestSupport.stepOne(HOST, "not-an-email");
        for (int i = 0; i < 30; i++) postStepOne(bad).andExpect(status().isBadRequest());
        postStepOne(bad).andExpect(status().isTooManyRequests());
    }
}
