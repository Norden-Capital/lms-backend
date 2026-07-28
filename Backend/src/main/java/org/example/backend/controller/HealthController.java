package org.example.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@RestController
public class HealthController {

    // Redirect root / → Swagger UI
    @GetMapping("/")
    public RedirectView redirectRoot() {
        return new RedirectView("/swagger-ui/index.html");
    }

    // Redirect /api/docs → Swagger UI
    @GetMapping("/api/docs")
    public RedirectView redirectApiDocs() {
        return new RedirectView("/swagger-ui/index.html");
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
