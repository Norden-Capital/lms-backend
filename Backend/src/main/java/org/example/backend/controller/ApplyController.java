package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.backend.dto.ApplyDtos;
import org.example.backend.exception.ApiException;
import org.example.backend.security.ClientIp;
import org.example.backend.service.ApplicationService;
import org.springframework.web.bind.annotation.*;

/**
 * The apply form's two steps. Open without an account, because visitors have
 * none, but only the form's own server can call it: it proves itself with the
 * shared secret ({@code X-Proxy-Secret}) and passes on the visitor's address,
 * which LeadScout requires. The browser never talks to this server directly.
 */
@RestController
@RequestMapping("/api/public/applications")
@Tag(name = "Apply form", description = "Called by the apply form's server only")
public class ApplyController {

    private final ApplicationService applications;
    private final ClientIp clientIp;

    public ApplyController(ApplicationService applications, ClientIp clientIp) {
        this.applications = applications;
        this.clientIp = clientIp;
    }

    @PostMapping
    @Operation(summary = "Save step 1", description = "The person is stored as abandoned until step 2 is finished.")
    public ApplyDtos.StepOneResult stepOne(@Valid @RequestBody ApplyDtos.StepOne form, HttpServletRequest request) {
        requireForm(request);
        return new ApplyDtos.StepOneResult(applications.saveStepOne(form, clientIp.resolve(request)));
    }

    @PostMapping("/{token}/finish")
    @Operation(summary = "Save step 2 and send to LeadScout", description = "Says what the form should show next.")
    public ApplyDtos.StepTwoResult stepTwo(@PathVariable String token,
                                           @Valid @RequestBody ApplyDtos.StepTwo form,
                                           HttpServletRequest request) {
        requireForm(request);
        return applications.finish(token, form);
    }

    private void requireForm(HttpServletRequest request) {
        if (!clientIp.viaWebsite(request)) {
            throw ApiException.forbidden("You do not have permission to do that.");
        }
        if (request.getHeader("X-Client-IP") == null) {
            throw ApiException.badRequest("The visitor's address is missing.");
        }
    }
}
