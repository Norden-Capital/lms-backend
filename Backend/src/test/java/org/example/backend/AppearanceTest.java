package org.example.backend;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.example.backend.repository.RoleRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settings → Theme: each person's look is saved on their account. Added 2026-11-11. */
@SpringBootTest(properties = "app.leadscout.mode=fake")
@AutoConfigureMockMvc
class AppearanceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApplyTestSupport.database(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;

    private RequestPostProcessor as(User user) {
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getEmail(), user.getRole().getName(), UUID.randomUUID());
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority(JwtAuthenticationFilter.SESSION_FULL))));
    }

    private User someone() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return users.save(User.builder()
                .publicId("NC-" + tag.substring(0, 4).toUpperCase() + "-" + tag.substring(4).toUpperCase())
                .firstName("Theme").lastName("Tester").email("theme-" + tag + "@example.com")
                .passwordHash("x").role(roles.findAll().get(0)).status(UserStatus.ACTIVE).build());
    }

    private JsonNode read(User user) throws Exception {
        return json.readTree(mvc.perform(get("/api/users/me/appearance").with(as(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void everyoneStartsWithTheStandardLook() throws Exception {
        JsonNode look = read(someone());
        assertThat(look.path("mode").asText()).isEqualTo("light");
        assertThat(look.path("palette").asText()).isEqualTo("default");
        assertThat(look.path("radius").asText()).isEqualTo("medium");
        assertThat(look.path("font").asText()).isEqualTo("geist");
        assertThat(look.path("cardFrame").asBoolean()).isTrue();
    }

    @Test
    void aSavedLookIsKeptOnTheAccount() throws Exception {
        User user = someone();
        String body = """
                {"mode":"dark","palette":"twitter","radius":"full","font":"inter","cardFrame":false}""";
        mvc.perform(put("/api/users/me/appearance").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        JsonNode look = read(user);
        assertThat(look.path("mode").asText()).isEqualTo("dark");
        assertThat(look.path("palette").asText()).isEqualTo("twitter");
        assertThat(look.path("radius").asText()).isEqualTo("full");
        assertThat(look.path("font").asText()).isEqualTo("inter");
        assertThat(look.path("cardFrame").asBoolean()).isFalse();
        assertThat(users.findById(user.getId()).orElseThrow().getAppearance()).contains("twitter");
    }

    @Test
    void somethingNotOnTheListsIsRefused() throws Exception {
        User user = someone();
        for (String body : new String[] {
                "{\"mode\":\"pink\",\"palette\":\"default\",\"radius\":\"medium\",\"font\":\"geist\",\"cardFrame\":true}",
                "{\"mode\":\"light\",\"palette\":\"<script>\",\"radius\":\"medium\",\"font\":\"geist\",\"cardFrame\":true}",
                "{\"mode\":\"light\",\"palette\":\"default\",\"radius\":\"huge\",\"font\":\"geist\",\"cardFrame\":true}",
                "{\"mode\":\"light\",\"palette\":\"default\",\"radius\":\"medium\",\"font\":\"comic\",\"cardFrame\":true}"}) {
            mvc.perform(put("/api/users/me/appearance").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(read(user).path("palette").asText()).isEqualTo("default");
    }

    @Test
    void youMustBeSignedIn() throws Exception {
        mvc.perform(get("/api/users/me/appearance")).andExpect(status().isUnauthorized());
    }
}
