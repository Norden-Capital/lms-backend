package org.example.backend;

import org.example.backend.entity.Brand;
import org.example.backend.repository.BrandRepository;
import org.example.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Leads, Abandoned and Failed lists, a client's page and Leads Overview, on a real database. */
@SpringBootTest(properties = "app.leadscout.mode=fake")
@AutoConfigureMockMvc
class ClientRecordsTest {

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BrandRepository brands;

    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void brands() {
        for (String domain : new String[] {"recordsone.ca", "recordstwo.ca"}) {
            if (brands.findByDomain(domain).isEmpty()) {
                brands.save(Brand.builder().publicId("BR-" + domain.substring(7, 10).toUpperCase() + "-TEST")
                        .name(domain).email("support@" + domain).domain(domain).active(true).build());
            }
        }
    }

    private static RequestPostProcessor staff(String... permissions) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL));
        for (String permission : permissions) authorities.add(new SimpleGrantedAuthority(permission));
        return user("staff").authorities(authorities);
    }

    private String email(String start) {
        return start + "-" + run + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
    }

    private String stepOne(String domain, String email) throws Exception {
        String body = mvc.perform(post("/api/public/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                        .header("X-Client-IP", "10.2." + (NEXT_ADDRESS.get() % 200) + "." + (NEXT_ADDRESS.getAndIncrement() % 200))
                        .content(json.writeValueAsString(ApplyTestSupport.stepOne("apply." + domain, email))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("stepToken").asString();
    }

    private void stepTwo(String token) throws Exception {
        mvc.perform(post("/api/public/applications/" + token + "/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                        .header("X-Client-IP", "10.3.0." + (NEXT_ADDRESS.getAndIncrement() % 200))
                        .content(json.writeValueAsString(ApplyTestSupport.stepTwo())))
                .andExpect(status().isOk());
    }

    private JsonNode read(String url) throws Exception {
        String body = mvc.perform(get(url).with(staff("CUSTOMER:READ")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void eachListHoldsItsOwnStatus() throws Exception {
        String lead = email("sold");
        String abandoned = email("sold");
        String failed = email("error");
        stepTwo(stepOne("recordsone.ca", lead));
        stepOne("recordsone.ca", abandoned);
        stepTwo(stepOne("recordsone.ca", failed));

        JsonNode leads = read("/api/applications?status=LEAD&search=" + lead);
        assertThat(leads.path("totalElements").asInt()).isEqualTo(1);
        JsonNode row = leads.path("content").get(0);
        assertThat(row.path("email").asString()).isEqualTo(lead);
        assertThat(row.path("name").asString()).isEqualTo("Secretname Tester");
        assertThat(row.path("phone").asString()).isEqualTo("(514) 555-1234");
        assertThat(row.path("brand").asString()).isEqualTo("recordsone.ca");
        assertThat(row.path("leadscoutStatus").asString()).isEqualTo("SOLD");
        assertThat(row.path("amount").asInt()).isEqualTo(1500);
        assertThat(row.path("test").asBoolean()).isTrue();

        assertThat(read("/api/applications?status=ABANDONED&search=" + abandoned).path("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/applications?status=LEAD&search=" + abandoned).path("totalElements").asInt()).isZero();
        assertThat(read("/api/applications?status=FAILED&search=" + failed).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void theNewestComesFirstAndPagesWork() throws Exception {
        String a = email("sold");
        String b = email("sold");
        stepOne("recordsone.ca", a);
        stepOne("recordsone.ca", b);

        JsonNode page = read("/api/applications?status=ABANDONED&size=1&search=-" + run + "-");
        assertThat(page.path("content").size()).isEqualTo(1);
        assertThat(page.path("totalElements").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("content").get(0).path("email").asString()).isEqualTo(b);
    }

    @Test
    void searchTreatsItsTextAsPlainText() throws Exception {
        stepOne("recordsone.ca", email("sold"));
        assertThat(read("/api/applications?status=ABANDONED&search=%25%25%25").path("totalElements").asInt()).isZero();
    }

    @Test
    void theBrandFilterWorks() throws Exception {
        String email = email("sold");
        stepOne("recordstwo.ca", email);
        String two = brands.findByDomain("recordstwo.ca").orElseThrow().getPublicId();
        String one = brands.findByDomain("recordsone.ca").orElseThrow().getPublicId();

        assertThat(read("/api/applications?status=ABANDONED&brand=" + two + "&search=" + email).path("totalElements").asInt()).isEqualTo(1);
        assertThat(read("/api/applications?status=ABANDONED&brand=" + one + "&search=" + email).path("totalElements").asInt()).isZero();
    }

    @Test
    void aClientsPageShowsEveryApplicationWithItsHistory() throws Exception {
        String email = email("flaky");
        stepOne("recordsone.ca", email);
        stepTwo(stepOne("recordstwo.ca", email));

        String clientId = read("/api/applications?status=LEAD&search=" + email).path("content").get(0).path("clientId").asString();
        JsonNode client = read("/api/clients/" + clientId);

        assertThat(client.path("email").asString()).isEqualTo(email);
        JsonNode applications = client.path("applications");
        assertThat(applications.size()).isEqualTo(2);

        JsonNode newest = applications.get(0);
        assertThat(newest.path("status").asString()).isEqualTo("LEAD");
        assertThat(newest.path("brand").asString()).isEqualTo("recordstwo.ca");
        assertThat(newest.path("answers").path("firstName").asString()).isEqualTo("Secretname");
        assertThat(newest.path("answers").path("monthlyIncome").asInt()).isEqualTo(2200);
        assertThat(newest.path("tracking").path("aff_utm_source").asString()).isEqualTo("google");
        assertThat(newest.path("tracking").has("not_allowed")).isFalse();
        assertThat(newest.path("ip").asString()).startsWith("10.");
        assertThat(newest.path("sends").size()).isEqualTo(2);
        assertThat(newest.path("sends").get(0).path("problem").asString()).isNotBlank();
        assertThat(newest.path("sends").get(1).path("leadscoutStatus").asString()).isEqualTo("SOLD");
        assertThat(newest.path("sends").get(1).path("request").asString()).contains("Secretname");

        assertThat(applications.get(1).path("status").asString()).isEqualTo("RETURNED");
    }

    @Test
    void anUnknownClientIsNotFound() throws Exception {
        mvc.perform(get("/api/clients/CL-NOPE-NOPE").with(staff("CUSTOMER:READ"))).andExpect(status().isNotFound());
    }

    @Test
    void theOverviewCountsByDayBrandAndAnswer() throws Exception {
        String brand = brands.findByDomain("recordstwo.ca").orElseThrow().getPublicId();
        JsonNode before = read("/api/applications/overview?days=7&includeTest=true&brand=" + brand);

        stepTwo(stepOne("recordstwo.ca", email("sold")));
        stepTwo(stepOne("recordstwo.ca", email("exists")));
        stepTwo(stepOne("recordstwo.ca", email("error")));
        stepOne("recordstwo.ca", email("sold"));

        JsonNode after = read("/api/applications/overview?days=7&includeTest=true&brand=" + brand);
        assertThat(after.path("leads").asInt() - before.path("leads").asInt()).isEqualTo(2);
        assertThat(after.path("failed").asInt() - before.path("failed").asInt()).isEqualTo(1);
        assertThat(after.path("abandoned").asInt() - before.path("abandoned").asInt()).isEqualTo(1);
        assertThat(after.path("leadscout").path("SOLD").asInt() - before.path("leadscout").path("SOLD").asInt()).isEqualTo(1);
        assertThat(after.path("leadscout").path("EXISTS").asInt() - before.path("leadscout").path("EXISTS").asInt()).isEqualTo(1);

        JsonNode daily = after.path("daily");
        assertThat(daily.size()).isEqualTo(7);
        assertThat(daily.get(6).path("leads").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void testApplicationsAreLeftOutOfTheOverviewByDefault() throws Exception {
        String brand = brands.findByDomain("recordsone.ca").orElseThrow().getPublicId();
        stepTwo(stepOne("recordsone.ca", email("sold")));

        assertThat(read("/api/applications/overview?brand=" + brand).path("leads").asInt()).isZero();
        assertThat(read("/api/applications/overview?includeTest=true&brand=" + brand).path("leads").asInt()).isPositive();
    }

    @Test
    void aBadPeriodOrListIsRefused() throws Exception {
        mvc.perform(get("/api/applications/overview?days=8").with(staff("CUSTOMER:READ"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/applications?status=SENDING").with(staff("CUSTOMER:READ"))).andExpect(status().isBadRequest());
    }

    @Test
    void onlyStaffWithThePermissionMayRead() throws Exception {
        for (String url : new String[] {"/api/applications?status=LEAD", "/api/applications/overview",
                "/api/applications/brands", "/api/clients/CL-NOPE-NOPE"}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(staff())).andExpect(status().isForbidden());
        }
    }
}
