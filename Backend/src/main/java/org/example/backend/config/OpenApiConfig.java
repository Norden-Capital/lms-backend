package org.example.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation metadata. Split out of the security configuration, which had
 * no business also describing the API.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LMS Backend API")
                        .version("1.0.0")
                        .description("""
                                Authentication and authorisation for the Learning Management System.

                                **Logging in with 2FA is two calls:** `POST /api/auth/login` returns
                                `mfaRequired: true` with a short-lived `mfaToken`; send that token plus the
                                6-digit code (or a recovery code) to `POST /api/auth/login/2fa` to receive
                                the real access token.

                                Click **Authorize** and paste an access token to try the protected endpoints."""))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth",
                                new SecurityScheme()
                                        .name("BearerAuth")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
