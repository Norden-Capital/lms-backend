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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    void existsWithALinkCountsAsGotTheLink() throws Exception {
        String domain = newBrand();
        stepTwo(stepOne(domain, email("existslink"), "email", null)); // EXISTS + link: sent to it
        stepTwo(stepOne(domain, email("exists"), "email", null));     // EXISTS, no link: "already applied"

        assertThat(steps(line(funnel(domain), "email"))).containsExactly(2, 2, 2, 1);
        assertThat(steps(line(funnel(domain, "tries"), "email")).get(3)).isEqualTo(1);
    }

    @Test
    void ibvPreStartCountsAsGotTheLink() throws Exception {
        String domain = newBrand();
        stepTwo(stepOne(domain, email("ibv"), "email", null)); // IBV_PRE_START + link: a lead, sent to it

        assertThat(steps(line(funnel(domain), "email"))).containsExactly(1, 1, 1, 1);
        assertThat(steps(line(funnel(domain, "tries"), "email")).get(3)).isEqualTo(1);
    }

    @Test
    void channelAndBrandRowsShowTheSameSevenColumns() throws Exception {
        String domain = newBrand();
        newBrand(); // another brand, left out when one is picked

        stepTwo(stepOne(domain, email("sold"), "email", null));   // lead
        stepOne(domain, email("sold"), "email", null);            // abandoned
        stepTwo(stepOne(domain, email("error"), "email", null));  // failed
        String twice = email("sold");                             // abandoned, then lead: counts as a lead
        stepOne(domain, twice, "email", null);
        stepTwo(stepOne(domain, twice, "email", null));
        stepTwo(stepOne(domain, email("sold"), null, null));      // SEO lead

        JsonNode overview = json.readTree(mvc.perform(get("/api/applications/overview?days=7&includeTest=true&brand=" + brandId(domain))
                        .with(user("staff").authorities(
                                new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL),
                                new SimpleGrantedAuthority("CUSTOMER:READ"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode email = line(overview.path("funnel"), "email");
        assertThat(List.of(email.path("leads").asInt(), email.path("abandoned").asInt(), email.path("failed").asInt()))
                .containsExactly(2, 1, 1);
        JsonNode seo = line(overview.path("funnel"), "seo");
        assertThat(List.of(seo.path("leads").asInt(), seo.path("abandoned").asInt(), seo.path("failed").asInt()))
                .containsExactly(1, 0, 0);

        // A picked brand is the only brand row, with the funnel's columns too.
        JsonNode rows = overview.path("brands");
        assertThat(rows.size()).isEqualTo(1);
        JsonNode row = rows.get(0);
        assertThat(row.path("id").asString()).isEqualTo(brandId(domain));
        assertThat(List.of(row.path("step1").asInt(), row.path("step2").asInt(), row.path("step3").asInt()))
                .containsExactly(5, 4, 3);
        assertThat(row.path("visitors").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(List.of(row.path("leads").asInt(), row.path("abandoned").asInt(), row.path("failed").asInt()))
                .containsExactly(3, 1, 1);
        // The brand row and the funnel's totals agree.
        assertThat(row.path("step1").asInt()).isEqualTo(overview.path("funnel").path("step1").asInt());
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
    void appliesAreCountedPerChannelAndBrand() throws Exception {
        String domain = newBrand();
        stepTwo(stepOne(domain, email("sold"), "email", null));
        stepTwo(stepOne(domain, email("error"), "email", null));
        stepTwo(stepOne(domain, email("sold"), null, null));
        // Stopped after step 1: not an apply.
        stepOne(domain, email("sold"), "email", null);
        // Tests are left out, so these count as real ones only from here.
        db.update("UPDATE loan_applications SET mode = 'live' WHERE brand_id = ?",
                brands.findByDomain(domain).orElseThrow().getId());

        var owner = authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "owner@example.com", "OWNER", null), null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("ROLE_OWNER"),
                        new SimpleGrantedAuthority("CHANNEL_SETUP:READ"), new SimpleGrantedAuthority("CHANNEL_SETUP:CREATE"),
                        new SimpleGrantedAuthority("CHANNEL_SETUP:UPDATE"))));
        JsonNode applies = json.readTree(mvc.perform(get("/api/channels/applies").with(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // [Q1, Q2, Q3, Q4] like Brand.applies: everything so far is in this quarter.
        int quarter = (java.time.LocalDate.now(java.time.ZoneOffset.UTC).getMonthValue() - 1) / 3;
        JsonNode email = applies.path("email").path(brandId(domain));
        JsonNode seo = applies.path("seo").path(brandId(domain));
        assertThat(email.size()).isEqualTo(4);
        assertThat(email.get(quarter).asInt()).isEqualTo(2);
        assertThat(seo.get(quarter).asInt()).isEqualTo(1);
        int total = 0;
        for (JsonNode count : email) total += count.asInt();
        assertThat(total).isEqualTo(2);
    }

    @Test
    void onlyTheOwnerManagesChannels() throws Exception {
        var owner = authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "owner@example.com", "OWNER", null), null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("ROLE_OWNER"),
                        new SimpleGrantedAuthority("CHANNEL_SETUP:READ"), new SimpleGrantedAuthority("CHANNEL_SETUP:CREATE"),
                        new SimpleGrantedAuthority("CHANNEL_SETUP:DELETE"))));
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
        // Channels are never deleted (2026-11-18): there is no DELETE any more.
        mvc.perform(delete("/api/channels/sms").with(owner)).andExpect(status().is4xxClientError());
        assertThat(db.queryForObject("SELECT count(*) FROM marketing_channels WHERE name = 'sms'", Integer.class)).isEqualTo(1);
        // Editing needs CHANNEL_SETUP:UPDATE.
        var reader = authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "reader@example.com", "ADMIN", null), null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("CHANNEL_SETUP:READ"))));
        mvc.perform(patch("/api/channels/sms").with(reader).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isForbidden());
    }

    private static final org.springframework.test.web.servlet.request.RequestPostProcessor OWNER = authentication(
            new UsernamePasswordAuthenticationToken(
                    new AuthPrincipal(null, "owner@example.com", "OWNER", null), null,
                    List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("ROLE_OWNER"),
                            new SimpleGrantedAuthority("CHANNEL_SETUP:READ"), new SimpleGrantedAuthority("CHANNEL_SETUP:CREATE"),
                            new SimpleGrantedAuthority("CHANNEL_SETUP:UPDATE"))));

    private JsonNode channel(JsonNode list, String name) {
        for (JsonNode c : list) if (c.path("name").asString().equals(name)) return c;
        return null;
    }

    private JsonNode patchChannel(String name, String body, int status) throws Exception {
        String answer = mvc.perform(patch("/api/channels/" + name).with(OWNER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return status == 200 ? json.readTree(answer) : null;
    }

    @Test
    void theListGivesEachChannelsLabelAndOnOffWithSeoLast() throws Exception {
        String name = "tv" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode list = json.readTree(mvc.perform(post("/api/channels").with(OWNER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode added = channel(list, name);
        assertThat(added.path("label").isNull()).isTrue();
        assertThat(added.path("active").asBoolean()).isTrue();
        assertThat(added.path("seo").asBoolean()).isFalse();
        JsonNode last = list.get(list.size() - 1);
        assertThat(last.path("name").asString()).isEqualTo("seo");
        assertThat(last.path("seo").asBoolean()).isTrue();
        assertThat(last.path("active").asBoolean()).isTrue();
    }

    @Test
    void aChannelIsTurnedOffNotDeletedAndItsLabelCanChange() throws Exception {
        String name = "tv" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(post("/api/channels").with(OWNER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isOk());

        JsonNode c = channel(patchChannel(name, "{\"label\":\"  TV Ads  \"}", 200), name);
        assertThat(c.path("label").asString()).isEqualTo("TV Ads");
        assertThat(c.path("active").asBoolean()).isTrue();

        c = channel(patchChannel(name, "{\"active\":false}", 200), name);
        assertThat(c.path("active").asBoolean()).isFalse();
        assertThat(c.path("label").asString()).isEqualTo("TV Ads");

        // Off but not gone: adding it again says so, and it still counts for the funnel.
        mvc.perform(post("/api/channels").with(OWNER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isConflict());
        assertThat(line(funnel(newBrand()), name)).isNotNull();

        // An empty label goes back to the tag.
        c = channel(patchChannel(name, "{\"label\":\"\",\"active\":true}", 200), name);
        assertThat(c.path("label").isNull()).isTrue();
        assertThat(c.path("active").asBoolean()).isTrue();

        // The tag itself can't change: a name in the body is ignored.
        c = channel(patchChannel(name, "{\"name\":\"radio\"}", 200), name);
        assertThat(c).isNotNull();

        patchChannel(name, "{\"label\":\"" + "x".repeat(61) + "\"}", 400);
        patchChannel(name, "{\"active\":\"no\"}", 400);
        patchChannel("nosuchchannel", "{\"active\":false}", 404);

        List<String> log = db.queryForList(
                "SELECT details FROM action_logs WHERE action = 'CHANNEL_UPDATED' AND details LIKE ? ORDER BY id", String.class, name + ":%");
        assertThat(log).containsExactly(
                name + ": label (none) → \"TV Ads\"",
                name + ": turned off",
                name + ": label \"TV Ads\" → (none), turned on");
    }

    @Test
    void seoCantBeChanged() throws Exception {
        String answer = mvc.perform(patch("/api/channels/seo").with(OWNER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(answer).contains("SEO can't be changed.");
        patchChannel("seo", "{\"label\":\"Search\"}", 400);
        patchChannel("seo", "{\"active\":true}", 400);
        assertThat(db.queryForObject("SELECT label FROM marketing_channels WHERE name = 'seo'", String.class)).isEqualTo("SEO");
    }

    @Test
    void visitsNeedTheFormsSecret() throws Exception {
        mvc.perform(post("/api/public/visits").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-IP", "10.4.9.9")
                        .content("{\"host\":\"apply.x.ca\",\"visitorId\":\"visitor-dddd-4444\"}"))
                .andExpect(status().isForbidden());
    }

    // ── People / Tries (2026-11-17) ──────────────────────────────────────

    private ResultActions overview(String domain, String view) throws Exception {
        return mvc.perform(get("/api/applications/overview?days=7&includeTest=true&brand=" + brandId(domain) + "&view=" + view)
                .with(user("staff").authorities(
                        new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL),
                        new SimpleGrantedAuthority("CUSTOMER:READ"))));
    }

    private JsonNode funnel(String domain, String view) throws Exception {
        return json.readTree(overview(domain, view).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("funnel");
    }

    /**
     * One person opens the form twice from fb, comes back from seo, saves step 1 both
     * times and gets a link; another is refused by LeadScout and clicks twice.
     */
    private String everyKindOfTry() throws Exception {
        String domain = newBrand();
        String twice = "v-" + UUID.randomUUID();
        visit(domain, twice, "fb");
        visit(domain, twice, "fb");
        visit(domain, "v-" + UUID.randomUUID(), null);

        String person = email("sold");
        String token = stepOne(domain, person, "fb", null);
        assertThat(stepOne(domain, person, null, null)).isEqualTo(token);
        stepTwo(token);

        String refused = stepOne(domain, email("error"), "fb", null);
        stepTwo(refused);
        stepTwo(refused);
        return domain;
    }

    @Test
    void triesCountEveryTimeWithEachVisitsChannel() throws Exception {
        JsonNode funnel = funnel(everyKindOfTry(), "tries");

        assertThat(funnel.path("view").asString()).isEqualTo("tries");
        assertThat(funnel.path("exactFrom").isNull()).isFalse();
        // fb: 2 openings, 2 step-1 saves, the refused person's 2 clicks, no link.
        assertThat(steps(line(funnel, "fb"))).containsExactly(2, 2, 2, 0);
        // seo: the return visit, its step-1 save, and the step 2 that followed it, with the link.
        assertThat(steps(line(funnel, "seo"))).containsExactly(1, 1, 1, 1);
        assertThat(List.of(funnel.path("visitors").asInt(), funnel.path("step1").asInt(),
                funnel.path("step2").asInt(), funnel.path("step3").asInt())).containsExactly(3, 3, 3, 1);
    }

    @Test
    void peopleIsStillTheDefault() throws Exception {
        String domain = everyKindOfTry();
        JsonNode people = funnel(domain, "people");
        assertThat(people.path("view").asString()).isEqualTo("people");
        assertThat(people.path("exactFrom").isNull()).isTrue();
        // Each person once, under their application's first channel.
        assertThat(steps(line(people, "fb"))).containsExactly(2, 2, 2, 1);
        assertThat(funnel(domain)).isEqualTo(people);
    }

    @Test
    void daysBeforeTheTriesStartedUseTheClosestNumbers() throws Exception {
        String domain = everyKindOfTry();
        java.sql.Timestamp start = db.queryForObject("SELECT started_at FROM form_tries_start", java.sql.Timestamp.class);
        try {
            // As if everything above happened before the tries were recorded.
            db.update("UPDATE form_tries_start SET started_at = now() + interval '1 hour'");
            JsonNode funnel = funnel(domain, "tries");
            // Applications (2, both first from fb), LeadScout sends (1 + the refused one's 2 x 2 with retries),
            // and applications with a link (1).
            assertThat(steps(line(funnel, "fb"))).containsExactly(2, 2, 5, 1);
            assertThat(steps(line(funnel, "seo"))).containsExactly(1, 0, 0, 0);
        } finally {
            db.update("UPDATE form_tries_start SET started_at = ?", start);
        }
    }

    @Test
    void anUnknownViewIsRefused() throws Exception {
        overview(newBrand(), "clicks").andExpect(status().isBadRequest());
    }
}
