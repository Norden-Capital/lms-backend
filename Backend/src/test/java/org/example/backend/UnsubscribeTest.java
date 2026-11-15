package org.example.backend;

import org.example.backend.dto.UpdatePermissionsRequest;
import org.example.backend.entity.Brand;
import org.example.backend.entity.PublicIds;
import org.example.backend.entity.Roles;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.repository.BrandRepository;
import org.example.backend.repository.RoleRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.security.JwtAuthenticationFilter;
import org.example.backend.service.PermissionService;
import org.example.backend.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Unsubscribes (V21, 2026-11-14): saved from the brand websites, listed and exported in the LMS. */
@SpringBootTest(properties = "app.leadscout.mode=fake")
@AutoConfigureMockMvc
class UnsubscribeTest {

    /** Every request gets its own address, so the rate limit never gets in the way of a test. */
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BrandRepository brands;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired UserPermissionService permissions;
    @Autowired PermissionService effective;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private String domain;
    private String brandId;

    @BeforeEach
    void brand() {
        domain = "unsub" + run + ".ca";
        brandId = PublicIds.generate("BR");
        brands.save(Brand.builder().publicId(brandId).name("Unsub " + run).email("support@" + domain)
                .domain(domain).active(true).build());
    }

    private String email(String start) {
        return start + "-" + run + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
    }

    private static String nextAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        return "10.9." + (n / 200 % 200) + "." + (n % 200);
    }

    private static Map<String, Object> form(String host, String email, String reason, String otherText) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("host", host);
        form.put("email", email);
        form.put("reason", reason);
        form.put("otherText", otherText);
        return form;
    }

    private MockHttpServletRequestBuilder send(Map<String, Object> form) throws Exception {
        return post("/api/public/unsubscribes")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                .header("X-Client-IP", nextAddress())
                .content(json.writeValueAsString(form));
    }

    private String unsubscribe(String host, String email, String reason, String otherText) throws Exception {
        return mvc.perform(send(form(host, email, reason, otherText)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor staff(String... permissions) {
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL));
        for (String permission : permissions) authorities.add(new SimpleGrantedAuthority(permission));
        return authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "staff@example.com", "MEMBER", null), null, authorities));
    }

    private JsonNode list(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/unsubscribes?" + query).with(staff("UNSUBSCRIBE:READ")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ── From the website ────────────────────────────────────────────────────

    @Test
    void anUnsubscribeIsSavedAndListed() throws Exception {
        String email = email("one");
        String answer = unsubscribe("www." + domain, email.toUpperCase(), "TOO_MANY_EMAILS", null);
        assertThat(json.readTree(answer).path("ok").asBoolean()).isTrue();

        JsonNode page = list("search=" + email);
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        JsonNode row = page.path("content").get(0);
        assertThat(row.path("email").asString()).isEqualTo(email);
        assertThat(row.path("brandId").asString()).isEqualTo(brandId);
        assertThat(row.path("brandDomain").asString()).isEqualTo(domain);
        assertThat(row.path("reason").asString()).isEqualTo("TOO_MANY_EMAILS");
        assertThat(row.path("ip").asString()).startsWith("10.9.");
        assertThat(row.path("id").asString()).startsWith("UN-");

        // The address is stored encrypted, never as plain text.
        String stored = db.queryForObject("SELECT ip FROM unsubscribes WHERE email = ?", String.class, email);
        assertThat(stored).doesNotStartWith("10.9.");
    }

    @Test
    void theSameEmailTwiceIsOneRowWithTheNewReason() throws Exception {
        String email = email("twice");
        unsubscribe(domain, email, "NOT_INTERESTED", null);
        unsubscribe(domain, " " + email.toUpperCase() + " ", "OTHER", "  Moved away  ");

        JsonNode page = list("search=" + email);
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        JsonNode row = page.path("content").get(0);
        assertThat(row.path("reason").asString()).isEqualTo("OTHER");
        assertThat(row.path("otherText").asString()).isEqualTo("Moved away");
        assertThat(row.path("updatedAt").asString()).isGreaterThanOrEqualTo(row.path("createdAt").asString());
    }

    @Test
    void theAnswerIsTheSameForAClientAndAStranger() throws Exception {
        String client = email("sold");
        mvc.perform(post("/api/public/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                        .header("X-Client-IP", nextAddress())
                        .content(json.writeValueAsString(ApplyTestSupport.stepOne("apply." + domain, client))))
                .andExpect(status().isOk());

        String forClient = unsubscribe(domain, client, "ALREADY_GOT_LOAN", null);
        String forStranger = unsubscribe(domain, email("stranger"), null, null);
        assertThat(forClient).isEqualTo(forStranger).isEqualTo("{\"ok\":true}");

        // The client is linked, with the name from their application, and their page shows it.
        JsonNode row = list("search=" + client).path("content").get(0);
        String clientId = row.path("clientId").asString();
        assertThat(clientId).startsWith("CL-");
        assertThat(row.path("leadName").asString()).isEqualTo("Secretname Tester");
        JsonNode page = json.readTree(mvc.perform(get("/api/clients/" + clientId).with(staff("CUSTOMER:READ")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("unsubscribes").size()).isEqualTo(1);
        assertThat(page.path("unsubscribes").get(0).path("brandDomain").asString()).isEqualTo(domain);
        assertThat(page.path("unsubscribes").get(0).path("reason").asString()).isEqualTo("ALREADY_GOT_LOAN");
    }

    @Test
    void aStrangerHasNoLeadUntilTheyApply() throws Exception {
        String email = email("later");
        unsubscribe(domain, email, null, null);
        JsonNode before = list("search=" + email).path("content").get(0);
        assertThat(before.path("clientId").isNull()).isTrue();
        assertThat(before.path("leadName").isNull()).isTrue();

        stepOne(email);
        JsonNode after = list("search=" + email).path("content").get(0);
        assertThat(after.path("clientId").asString()).startsWith("CL-");
        assertThat(after.path("leadName").asString()).isEqualTo("Secretname Tester");
    }

    private void stepOne(String email) throws Exception {
        mvc.perform(post("/api/public/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET)
                        .header("X-Client-IP", nextAddress())
                        .content(json.writeValueAsString(ApplyTestSupport.stepOne("apply." + domain, email))))
                .andExpect(status().isOk());
    }

    @Test
    void noReasonIsAllowed() throws Exception {
        String email = email("none");
        unsubscribe(domain, email, "", null);
        assertThat(list("search=" + email).path("content").get(0).path("reason").isNull()).isTrue();
    }

    @Test
    void aSwitchedOffBrandStillTakesUnsubscribes() throws Exception {
        db.update("UPDATE brands SET active = FALSE WHERE domain = ?", domain);
        String email = email("off");
        unsubscribe(domain, email, null, null);
        assertThat(list("search=" + email).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void onlyTheWebsitesServerCanSave() throws Exception {
        String body = json.writeValueAsString(form(domain, email("x"), null, null));
        mvc.perform(post("/api/public/unsubscribes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/unsubscribes").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", "wrong").header("X-Client-IP", nextAddress()).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/unsubscribes").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Proxy-Secret", ApplyTestSupport.SECRET).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badFormsAreRefused() throws Exception {
        mvc.perform(send(form(domain, "not-an-email", null, null))).andExpect(status().isBadRequest());
        mvc.perform(send(form(domain, email("r"), "SPAM", null))).andExpect(status().isBadRequest());
        mvc.perform(send(form(domain, email("t"), "OTHER", "x".repeat(301)))).andExpect(status().isBadRequest());
        mvc.perform(send(form("not-a-brand-" + run + ".ca", email("h"), null, null))).andExpect(status().isBadRequest());
    }

    @Test
    void oneAddressCannotSendTooMany() throws Exception {
        String address = "10.8." + (NEXT_ADDRESS.getAndIncrement() % 200) + ".1";
        String body = json.writeValueAsString(form(domain, email("flood"), null, null));
        int refused = 0;
        for (int i = 0; i < 12; i++) {
            int code = mvc.perform(post("/api/public/unsubscribes").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Proxy-Secret", ApplyTestSupport.SECRET).header("X-Client-IP", address).content(body))
                    .andReturn().getResponse().getStatus();
            if (code == 429) refused++;
        }
        assertThat(refused).isEqualTo(2);
    }

    // ── In the LMS ──────────────────────────────────────────────────────────

    @Test
    void theListFiltersByBrandReasonAndDate() throws Exception {
        String a = email("filter");
        String b = email("filter");
        unsubscribe(domain, a, "NOT_INTERESTED", null);
        unsubscribe(domain, b, "NEVER_SIGNED_UP", null);
        db.update("UPDATE unsubscribes SET updated_at = '2020-01-15T12:00:00Z' WHERE email = ?", b);

        assertThat(list("brand=" + brandId).path("totalElements").asInt()).isEqualTo(2);
        assertThat(list("brand=" + brandId + "&reason=NOT_INTERESTED").path("content").get(0).path("email").asString()).isEqualTo(a);
        JsonNode old = list("brand=" + brandId + "&from=2020-01-01&to=2020-01-31");
        assertThat(old.path("totalElements").asInt()).isEqualTo(1);
        assertThat(old.path("content").get(0).path("email").asString()).isEqualTo(b);

        mvc.perform(get("/api/unsubscribes?reason=NOPE").with(staff("UNSUBSCRIBE:READ"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/unsubscribes?from=2020-02-01&to=2020-01-01").with(staff("UNSUBSCRIBE:READ")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/unsubscribes?from=yesterday").with(staff("UNSUBSCRIBE:READ"))).andExpect(status().isBadRequest());
    }

    @Test
    void theListCanBeSorted() throws Exception {
        String a = "aaa" + email("sort");
        String z = "zzz" + email("sort");
        unsubscribe(domain, z, "NOT_INTERESTED", null);
        unsubscribe(domain, a, "TOO_MANY_EMAILS", null);
        db.update("UPDATE unsubscribes SET updated_at = '2020-01-15T12:00:00Z' WHERE email = ?", a);

        // Newest first by default.
        assertThat(list("brand=" + brandId).path("content").get(0).path("email").asString()).isEqualTo(z);
        assertThat(list("brand=" + brandId + "&sort=date,asc").path("content").get(0).path("email").asString()).isEqualTo(a);
        assertThat(list("brand=" + brandId + "&sort=email,asc").path("content").get(0).path("email").asString()).isEqualTo(a);
        assertThat(list("brand=" + brandId + "&sort=email,desc").path("content").get(0).path("email").asString()).isEqualTo(z);
        assertThat(list("brand=" + brandId + "&sort=reason,asc").path("content").get(0).path("reason").asString())
                .isEqualTo("NOT_INTERESTED");
        assertThat(list("brand=" + brandId + "&sort=brand,asc").path("totalElements").asInt()).isEqualTo(2);

        mvc.perform(get("/api/unsubscribes?sort=ip,asc").with(staff("UNSUBSCRIBE:READ"))).andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheTickedRowsAreExported() throws Exception {
        String kept = email("ticked");
        String left = email("ticked");
        unsubscribe(domain, kept, null, null);
        unsubscribe(domain, left, null, null);
        String id = list("search=" + kept).path("content").get(0).path("id").asString();

        // ids wins over the filters: the reason filter would match neither row.
        String csv = mvc.perform(get("/api/unsubscribes/export?reason=OTHER&ids=" + id).with(staff("UNSUBSCRIBE:EXPORT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains(kept).doesNotContain(left);
        assertThat(csv.lines().filter(line -> !line.isBlank()).count()).isEqualTo(2);

        // Without ids, the filters again.
        String all = mvc.perform(get("/api/unsubscribes/export?brand=" + brandId).with(staff("UNSUBSCRIBE:EXPORT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(all).contains(kept, left);
    }

    @Test
    void theListAndExportNeedTheirPermissions() throws Exception {
        mvc.perform(get("/api/unsubscribes").with(staff("CUSTOMER:READ"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/unsubscribes/brands").with(staff("CUSTOMER:READ"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/unsubscribes/export").with(staff("UNSUBSCRIBE:READ"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/unsubscribes/brands").with(staff("UNSUBSCRIBE:READ"))).andExpect(status().isOk());
        mvc.perform(get("/api/unsubscribes").with(user("nobody"))).andExpect(status().isForbidden());
    }

    @Test
    void theExportIsACsvThatASpreadsheetCannotRun() throws Exception {
        String email = email("csv");
        unsubscribe(domain, email, "OTHER", "=HYPERLINK(\"http://bad\")");

        byte[] file = mvc.perform(get("/api/unsubscribes/export?brand=" + brandId).with(staff("UNSUBSCRIBE:EXPORT")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(file, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Date (UTC),Email,Brand,Reason,Other text,Client ID,First time (UTC)");
        assertThat(csv).contains("\"" + email + "\"", "\"OTHER\"", "\"'=HYPERLINK(\"\"http://bad\"\")\"");
        assertThat(db.queryForObject("SELECT count(*) FROM action_logs WHERE action = 'UNSUBSCRIBES_EXPORTED'", Integer.class))
                .isPositive();
    }

    @Test
    void aBrandWithUnsubscribesCannotBeRemoved() throws Exception {
        unsubscribe(domain, email("keep"), null, null);
        var owner = authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "owner@example.com", "OWNER", null), null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL), new SimpleGrantedAuthority("ROLE_OWNER"),
                        new SimpleGrantedAuthority("BRAND:DELETE"))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/brands/" + brandId).with(owner))
                .andExpect(status().isConflict());
    }

    /**
     * Both exports write an Action Log line under the real person who pressed the
     * button. Found by DevOps 2026-11-15: the export ran read-only, so PostgreSQL
     * refused that line and the download failed with 500.
     */
    @Test
    void bothExportsWorkAndAreLoggedUnderThePerson() throws Exception {
        String email = email("logged");
        unsubscribe(domain, email, null, null);
        String id = list("search=" + email).path("content").get(0).path("id").asString();

        User admin = make(Roles.ADMIN);
        var person = authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(admin.getId(), admin.getEmail(), Roles.ADMIN, null), null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL),
                        new SimpleGrantedAuthority("UNSUBSCRIBE:EXPORT"))));

        mvc.perform(get("/api/unsubscribes/export?ids=" + id).with(person)).andExpect(status().isOk());
        mvc.perform(get("/api/unsubscribes/export?search=" + email).with(person)).andExpect(status().isOk());

        List<String> lines = db.queryForList(
                "SELECT details FROM action_logs WHERE action = 'UNSUBSCRIBES_EXPORTED' AND actor_id = ? ORDER BY id",
                String.class, admin.getId());
        assertThat(lines).containsExactly("1 rows", "1 rows");
    }

    // ── Who holds it ────────────────────────────────────────────────────────

    private User make(String role) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return users.save(User.builder()
                .publicId(PublicIds.generate())
                .firstName("Test").lastName(role)
                .email(role.toLowerCase() + "-unsub-" + tag + "@example.com")
                .passwordHash("!unused")
                .role(roles.findByName(role).orElseThrow())
                .status(UserStatus.ACTIVE)
                .build());
    }

    private java.util.Set<String> held(User user) {
        return effective.effectivePermissionsFor(users.findWithRoleById(user.getId()).orElseThrow());
    }

    @Test
    void administratorsHoldItAndAMemberCanBeGivenIt() {
        assertThat(held(make(Roles.ADMIN))).contains("UNSUBSCRIBE:READ", "UNSUBSCRIBE:EXPORT");

        User owner = users.findAll().stream().filter(u -> Roles.OWNER.equals(u.getRole().getName()))
                .findFirst().orElseGet(() -> make(Roles.OWNER));
        assertThat(held(owner)).contains("UNSUBSCRIBE:READ", "UNSUBSCRIBE:EXPORT");

        User member = make(Roles.MEMBER);
        assertThat(held(member)).doesNotContain("UNSUBSCRIBE:READ");
        permissions.replaceFor(member.getId(), new UpdatePermissionsRequest(List.of("UNSUBSCRIBE:READ"), List.of(), null),
                owner.getId());
        assertThat(held(member)).contains("UNSUBSCRIBE:READ").doesNotContain("UNSUBSCRIBE:EXPORT");
    }
}
