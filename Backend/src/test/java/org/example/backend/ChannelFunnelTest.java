package org.example.backend;

import org.example.backend.entity.Brand;
import org.example.backend.repository.BrandRepository;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Where leads come from: the channel on each lead, visitors, and the funnel on Leads Overview. */
@SpringBootTest(properties = "app.leadscout.mode=fake")
@AutoConfigureMockMvc
class ChannelFunnelTest {

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BrandRepository brands;
    @Autowired JdbcTemplate db;

    /** Each test gets a brand of its own, so its counts are its own. */
    private String newBrand() {
        String domain = "funnel" + UUID.randomUUID().toString().substring(0, 6) + ".ca";
        brands.save(Brand.builder().publicId("BR-" + domain.substring(6, 12).toUpperCase())
                .name(domain).email("support@" + domain).domain(domain).active(true).build());
        return domain;
    }

    private String brandId(String domain) {
        return brands.findByDomain(domain).orElseThrow().getPublicId();
    }

    private static String email(String start) {
        return start + "-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions publicPost(String path, Object body) throws Exception {
        return mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                .header("X-Client-IP", "10.4.0." + (NEXT_ADDRESS.getAndIncrement() % 200))
                .content(json.writeValueAsString(body)));
    }

    /** Step 1 with this utm_source, or none. */
    private String stepOne(String domain, String email, String source, String token) throws Exception {
        Map<String, Object> form = ApplyTestSupport.stepOne("apply." + domain, email);
        form.put("extra", source == null ? Map.of() : Map.of("aff_utm_source", source));
        if (token != null) form.put("stepToken", token);
        String body = publicPost("/api/public/applications", form)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("stepToken").asString();
    }

    private void stepTwo(String token) throws Exception {
        publicPost("/api/public/applications/" + token + "/finish", ApplyTestSupport.stepTwo()).andExpect(status().isOk());
    }

    private void visit(String domain, String visitor, String source) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "apply." + domain);
        body.put("visitorId", visitor);
        body.put("source", source);
        publicPost("/api/public/visits", body).andExpect(status().isOk());
    }

    private String channelOf(String token) {
        return db.queryForObject("SELECT channel FROM loan_applications WHERE step_token = ?", String.class, token);
    }

    private JsonNode funnel(String domain) throws Exception {
        String body = mvc.perform(get("/api/applications/overview?days=7&includeTest=true&brand=" + brandId(domain))
                        .with(user("staff").authorities(
                                new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL),
                                new SimpleGrantedAuthority("CUSTOMER:READ"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("funnel");
    }

    private static JsonNode line(JsonNode funnel, String channel) {
        for (JsonNode line : funnel.path("channels")) {
            if (line.path("channel").asString().equals(channel)) return line;
        }
        throw new AssertionError("No line for " + channel);
    }

    private static List<Integer> steps(JsonNode line) {
        return List.of(line.path("visitors").asInt(), line.path("step1").asInt(),
                line.path("step2").asInt(), line.path("step3").asInt());
    }

    @Test
    void theTagIsTheChannelAndNoTagIsSeo() throws Exception {
        String domain = newBrand();
        assertThat(channelOf(stepOne(domain, email("sold"), "email", null))).isEqualTo("email");
        assertThat(channelOf(stepOne(domain, email("sold"), " Facebook ", null))).isEqualTo("facebook");
        assertThat(channelOf(stepOne(domain, email("sold"), null, null))).isEqualTo("seo");
        assertThat(channelOf(stepOne(domain, email("sold"), "<script>", null))).isEqualTo("script");
        assertThat(channelOf(stepOne(domain, email("sold"), "!!!", null))).isEqualTo("seo");
    }

    @Test
    void aRefreshOrAVisitAgainTheSameDayIsCountedOnce() throws Exception {
        String domain = newBrand();
        visit(domain, "visitor-aaaa-1111", "email");
        visit(domain, "visitor-aaaa-1111", "email");
        visit(domain, "visitor-aaaa-1111", null);
        visit(domain, "visitor-bbbb-2222", null);
        visit("not-a-brand.ca", "visitor-cccc-3333", "email");

        JsonNode funnel = funnel(domain);
        assertThat(funnel.path("visitors").asInt()).isEqualTo(2);
        assertThat(line(funnel, "email").path("visitors").asInt()).isEqualTo(1);
        assertThat(line(funnel, "seo").path("visitors").asInt()).isEqualTo(1);
    }

    @Test
    void eachPersonCountsOnceAtEachStep() throws Exception {
        String domain = newBrand();

        // Email: one person saves step 1, refreshes and saves it again, then finishes and gets the link.
        String person = email("sold");
        stepOne(domain, person, "email", null);
        stepTwo(stepOne(domain, person, "email", null));
        // Previous and Next again on the same application.
        String back = stepOne(domain, email("sold"), "email", null);
        stepOne(domain, email("ignored"), "email", back);
        // One stops after step 1, one is refused by LeadScout.
        stepOne(domain, email("sold"), "email", null);
        stepTwo(stepOne(domain, email("error"), "email", null));

        // SEO: one finishes.
        stepTwo(stepOne(domain, email("sold"), null, null));

        // No visits were counted, but everyone on step 1 was on the form.
        JsonNode funnel = funnel(domain);
        assertThat(steps(line(funnel, "email"))).containsExactly(4, 4, 2, 1);
        assertThat(steps(line(funnel, "seo"))).containsExactly(1, 1, 1, 1);
        assertThat(List.of(funnel.path("step1").asInt(), funnel.path("step2").asInt(), funnel.path("step3").asInt()))
                .containsExactly(5, 3, 2);
    }

    @Test
    void everyChannelWithALinkHasALineEvenWithNothingYet() throws Exception {
        JsonNode funnel = funnel(newBrand());
        assertThat(steps(line(funnel, "email"))).containsExactly(0, 0, 0, 0);
        assertThat(steps(line(funnel, "seo"))).containsExactly(0, 0, 0, 0);
    }

    @Test
    void theListCanShowOneChannel() throws Exception {
        String domain = newBrand();
        String tagged = email("sold");
        stepOne(domain, tagged, "email", null);
        stepOne(domain, email("sold"), null, null);

        String body = mvc.perform(get("/api/applications?status=ABANDONED&channel=email&brand=" + brandId(domain))
                        .with(user("staff").authorities(
                                new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL),
                                new SimpleGrantedAuthority("CUSTOMER:READ"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode page = json.readTree(body);
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("email").asString()).isEqualTo(tagged);
        assertThat(page.path("content").get(0).path("channel").asString()).isEqualTo("email");
    }

    @Test
    void onlyTheOwnerManagesChannels() throws Exception {
        var owner = authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "owner@example.com", "OWNER", null), null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("ROLE_OWNER"))));
        var staff = user("staff").authorities(
                new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("CUSTOMER:READ"));

        mvc.perform(get("/api/channels").with(staff)).andExpect(status().isForbidden());
        mvc.perform(post("/api/channels").with(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bad Name\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/channels").with(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"seo\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/channels").with(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sms\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/channels").with(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sms\"}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/channels/sms").with(owner)).andExpect(status().isOk());
        mvc.perform(delete("/api/channels/sms").with(owner)).andExpect(status().isNotFound());
    }

    @Test
    void visitsNeedTheFormsSecret() throws Exception {
        mvc.perform(post("/api/public/visits").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-IP", "10.4.9.9")
                        .content("{\"host\":\"apply.x.ca\",\"visitorId\":\"visitor-dddd-4444\"}"))
                .andExpect(status().isForbidden());
    }
}
