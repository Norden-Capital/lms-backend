package org.example.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Swagger UI page directly at /api/docs.
 *
 * Springdoc's own "springdoc.swagger-ui.path" only ever 302-redirects to its
 * bundled index.html, which moves the address bar off /api/docs. That page just
 * loads the swagger-ui assets using relative URLs, so it has to live in the
 * asset folder. Rendering the same page here with absolute asset URLs keeps the
 * browser on /api/docs with no redirect.
 */
@RestController
public class DocsController {

    @GetMapping(value = "/api/docs", produces = MediaType.TEXT_HTML_VALUE)
    public String swaggerUi() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8">
                    <title>LMS Backend API Documentation</title>
                    <link rel="stylesheet" type="text/css" href="/swagger-ui/swagger-ui.css" />
                    <link rel="stylesheet" type="text/css" href="/swagger-ui/index.css" />
                    <link rel="icon" type="image/png" href="/swagger-ui/favicon-32x32.png" sizes="32x32" />
                  </head>
                  <body>
                    <div id="swagger-ui"></div>
                    <script src="/swagger-ui/swagger-ui-bundle.js" charset="UTF-8"></script>
                    <script src="/swagger-ui/swagger-ui-standalone-preset.js" charset="UTF-8"></script>
                    <script>
                      window.onload = function () {
                        window.ui = SwaggerUIBundle({
                          configUrl: "/v3/api-docs/swagger-config",
                          dom_id: "#swagger-ui",
                          deepLinking: true,
                          presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                          plugins: [SwaggerUIBundle.plugins.DownloadUrl],
                          layout: "StandaloneLayout",
                          validatorUrl: ""
                        });
                      };
                    </script>
                  </body>
                </html>
                """;
    }
}
