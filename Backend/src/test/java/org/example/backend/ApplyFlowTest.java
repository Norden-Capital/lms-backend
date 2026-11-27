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
import java.util.List;
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
    @Autowired org.example.backend.service.EncryptionService encryption;
    @Autowired org.example.backend.service.LeadScoutClient leadScout;

    @Test
    void leadScoutGetsFortyFiveSecondsToAnswer() {
        // Owner, 2026-11-20: 45 s (was 20).
        assertThat(leadScout.readTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
    }

    /** Each test is its own visitor. */
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

    @Test
    void everySendIsMarkedAsOursAndHidesOurChannels() throws Exception {
        Map<String, Object> form = ApplyTestSupport.stepOne(HOST, email("sold"));
        form.put("extra", Map.of("aff_utm_source", "facebook", "aff_utm_medium", "cpc",
                "aff_utm_campaign", "black-friday", "aff_utm_content", "ad-2", "aff_utm_term", "loan"));
        String token = json.readTree(postStepOne(form).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("stepToken").asString();
        finish(token);

        String request = db.queryForObject("""
                SELECT e.request FROM application_events e JOIN loan_applications a ON a.id = e.application_id
                WHERE a.step_token = ?""", String.class, token);
        JsonNode extra = json.readTree(encryption.decrypt(request)).path("extra");
        // Exactly these three: LeadScout's Source is always API, and nothing about our channels.
        List<String> names = new java.util.ArrayList<>();
        extra.propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrder("aff_utm_source", "affiliate_sub1", "affiliate_sub2");
        assertThat(extra.path("aff_utm_source").asString()).isEqualTo("API");
        assertThat(extra.path("affiliate_sub1").asString()).isEqualTo("testbrand.ca");
        assertThat(extra.path("affiliate_sub2").asString()).startsWith("AP");

        // The LMS still keeps the visitor's own channel and tags.
        assertThat(db.queryForObject("SELECT channel FROM loan_applications WHERE step_token = ?", String.class, token))
                .isEqualTo("facebook");
        String answers = db.queryForObject("SELECT answers FROM loan_applications WHERE step_token = ?", String.class, token);
        JsonNode kept = json.readTree(encryption.decrypt(answers)).path("extra");
        assertThat(kept.path("aff_utm_source").asString()).isEqualTo("facebook");
        assertThat(kept.path("aff_utm_campaign").asString()).isEqualTo("black-friday");
    }

    @ParameterizedTest
    @CsvSource({
            // email start, status in LMS, what the form shows, sends
            "sold,    LEAD,   GO_TO_LINK,      1",
            "cpf,     LEAD,   GO_TO_LINK,      1",
            "notsold, LEAD,   GO_TO_LINK,      1",
            "exists,  LEAD,   ALREADY_APPLIED, 1",
            // Owner, 2026-11-24: EXISTS with LeadScout's link sends the person to it.
            "existslink, LEAD, GO_TO_LINK,     1",
            // Owner, 2026-11-27: IBV_PRE_START is a lead with a link, like SOLD.
            "ibv,     LEAD,   GO_TO_LINK,      1",
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
        String saved = db.queryForObject("SELECT redirect_url FROM loan_applications WHERE step_token = ?", String.class, token);
        if ("GO_TO_LINK".equals(outcome)) {
            assertThat(result.path("redirectUrl").asString()).startsWith("https://");
            assertThat(saved).isEqualTo(result.path("redirectUrl").asString());
        } else {
            assertThat(result.path("redirectUrl").isNull()).isTrue();
            assertThat(saved).isNull();
        }
    }

    @Test
    void ibvPreStartKeepsLeadScoutsOwnWord() throws Exception {
        String token = startApplication(email("ibv"));

        JsonNode result = finish(token);

        assertThat(result.path("outcome").asString()).isEqualTo("GO_TO_LINK");
        assertThat(statusOf(token)).isEqualTo("LEAD");
        assertThat(db.queryForObject("SELECT leadscout_status FROM loan_applications WHERE step_token = ?", String.class, token))
                .isEqualTo("IBV_PRE_START");
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

    private int applicationsOf(String email) {
        return count("""
                SELECT count(*) FROM loan_applications a JOIN clients c ON c.id = a.client_id
                WHERE c.email = ?""", email);
    }

    private String startOn(String host, String email, String source) throws Exception {
        Map<String, Object> form = ApplyTestSupport.stepOne(host, email);
        form.put("extra", source == null ? Map.of() : Map.of("aff_utm_source", source));
        return json.readTree(postStepOne(form).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("stepToken").asString();
    }

    /**
     * Step 1 again without finishing, same email and brand — even days later: the
     * same abandoned application, with the newest answers but its first channel and
     * created date. Not a second row (owner, 2026-11-17).
     */
    @Test
    void startingAgainReusesTheUnfinishedApplication() throws Exception {
        String email = email("sold");
        String first = startOn(HOST, email, "fb");
        db.update("UPDATE loan_applications SET created_at = now() - interval '3 days', "
                + "updated_at = now() - interval '3 days' WHERE step_token = ?", first);

        Map<String, Object> again = ApplyTestSupport.stepOne(HOST, email.toUpperCase());
        again.put("requestedAmount", 2500);
        again.put("extra", Map.of());
        String second = json.readTree(postStepOne(again).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("stepToken").asString();

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM clients WHERE email = ?", email)).isEqualTo(1);
        assertThat(applicationsOf(email)).isEqualTo(1);
        assertThat(count("SELECT requested_amount FROM loan_applications WHERE step_token = ?", first)).isEqualTo(2500);
        assertThat(db.queryForObject("SELECT channel FROM loan_applications WHERE step_token = ?", String.class, first))
                .isEqualTo("fb");
        assertThat(count("SELECT count(*) FROM loan_applications WHERE step_token = ? "
                + "AND created_at < now() - interval '2 days' AND updated_at > now() - interval '1 hour'", first)).isEqualTo(1);

        // It finishes as that same row, and the LMS still holds the first source.
        finish(second);
        assertThat(statusOf(first)).isEqualTo("LEAD");
        String answers = db.queryForObject("SELECT answers FROM loan_applications WHERE step_token = ?", String.class, first);
        assertThat(json.readTree(encryption.decrypt(answers)).path("extra").path("aff_utm_source").asString()).isEqualTo("fb");
    }

    @Test
    void aFinishedApplicationIsNeverReused() throws Exception {
        String email = email("sold");
        String first = startApplication(email);
        finish(first);

        String second = startApplication(email);
        assertThat(second).isNotEqualTo(first);
        assertThat(applicationsOf(email)).isEqualTo(2);
    }

    @Test
    void anotherBrandIsAnotherApplication() throws Exception {
        if (brands.findByDomain("otherbrand.ca").isEmpty()) brands.save(brand("otherbrand.ca", true));
        String email = email("sold");
        String first = startApplication(email);

        String otherBrand = startOn("apply.otherbrand.ca", email, null);
        assertThat(otherBrand).isNotEqualTo(first);
        // Back on the first brand, a week later: still the first application.
        db.update("UPDATE loan_applications SET updated_at = now() - interval '7 days' WHERE step_token = ?", first);
        assertThat(startApplication(email)).isEqualTo(first);
        assertThat(applicationsOf(email)).isEqualTo(2);
    }

    @Test
    void becomingALeadTakesOldTriesOutOfAbandoned() throws Exception {
        if (brands.findByDomain("otherbrand.ca").isEmpty()) brands.save(brand("otherbrand.ca", true));
        String email = email("sold");
        // An unfinished try on another brand.
        String oldTry = startOn("apply.otherbrand.ca", email, null);
        String newTry = startApplication(email);
        assertThat(newTry).isNotEqualTo(oldTry);

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

        // LeadScout's minimums: home value 1,000 and mortgage 300.
        owner.put("propertyValue", 999);
        owner.put("propertyMortgage", 300);
        postStepTwo(token, owner).andExpect(status().isBadRequest());
        owner.put("propertyValue", 1000);
        owner.put("propertyMortgage", 299);
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
    void oneAddressCanApplyAsOftenAsItWants() throws Exception {
        Map<String, Object> bad = ApplyTestSupport.stepOne(HOST, "not-an-email");
        for (int i = 0; i < 31; i++) postStepOne(bad).andExpect(status().isBadRequest());
    }

    @Test
    void signInIsStillLimited() throws Exception {
        for (int i = 0; i < 10; i++) postSignIn().andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        postSignIn().andExpect(status().isTooManyRequests());
    }

    private ResultActions postSignIn() throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                .header("X-Client-IP", address)
                .content("{}"));
    }
}
