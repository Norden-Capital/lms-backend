package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.backend.dto.ApplyDtos;
import org.example.backend.exception.ApiException;
import org.example.backend.security.ClientIp;
import org.example.backend.service.ApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The apply form's two steps, and its visitors. Open without an account, because visitors have
 * none, but only the form's own server can call it: it proves itself with the
 * shared secret ({@code X-Proxy-Secret}) and passes on the visitor's address,
 * which LeadScout requires. The browser never talks to this server directly.
 */
@RestController
@RequestMapping("/api/public")
@Tag(name = "Apply form", description = "Called by the apply form's server only")
public class ApplyController {

    /** Visits are counted per day on the business calendar. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Toronto");

    private final ApplicationService applications;
    private final ClientIp clientIp;

    public ApplyController(ApplicationService applications, ClientIp clientIp) {
        this.applications = applications;
        this.clientIp = clientIp;
    }

    @PostMapping("/visits")
    @Operation(summary = "Someone opened the form", description = "Counted once per browser, brand and day.")
    public ResponseEntity<Void> visit(@Valid @RequestBody ApplyDtos.Visit visit, HttpServletRequest request) {
        requireForm(request);
        applications.recordVisit(visit, LocalDate.now(BUSINESS_ZONE));
        // 200, not 204: the form's server answers the browser with the same status, and a 204 can't carry its body.
        return ResponseEntity.ok().build();
    }

    @PostMapping("/applications")
    @Operation(summary = "Save step 1", description = "The person is stored as abandoned until step 2 is finished.")
    public ApplyDtos.StepOneResult stepOne(@Valid @RequestBody ApplyDtos.StepOne form, HttpServletRequest request) {
        requireForm(request);
        return new ApplyDtos.StepOneResult(applications.saveStepOne(form, clientIp.resolve(request)));
    }

    @PostMapping("/applications/{token}/finish")
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
