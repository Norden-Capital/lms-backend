package org.example.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@RestController
public class HealthController {

    private final boolean docsEnabled;

    public HealthController(@Value("${app.docs.enabled:false}") boolean docsEnabled) {
        this.docsEnabled = docsEnabled;
    }

    // Root / → Swagger UI when the docs are on, else the health check.
    @GetMapping("/")
    public RedirectView redirectRoot() {
        return new RedirectView(docsEnabled ? "/api/docs" : "/health");
    }

    @GetMapping("/health")
    public Map<String, String> healthCheck() {
        return Map.of(
            "status", "UP",
            "service", "LMS Backend API",
            "timestamp", String.valueOf(System.currentTimeMillis())
        );
    }
}
