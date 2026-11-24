package org.example.backend.service;

import org.example.backend.dto.ApplyDtos;
import org.example.backend.dto.ApplyDtos.StepTwoResult;
import org.example.backend.entity.ApplicationEvent;
import org.example.backend.entity.ApplicationStatus;
import org.example.backend.entity.Brand;
import org.example.backend.entity.Client;
import org.example.backend.entity.LoanApplication;
import org.example.backend.entity.PublicIds;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.ApplicationEventRepository;
import org.example.backend.repository.BrandRepository;
import org.example.backend.repository.ClientRepository;
import org.example.backend.repository.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The apply form's two steps. Step 1 saves the person as abandoned; step 2 saves
 * the rest <b>before</b> anything is sent, so a failure at LeadScout never loses a lead.
 *
 * <p>The send itself runs outside any transaction: it can take many seconds, and
 * a database connection should not wait on someone else's server.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /** The only tracking fields LeadScout takes from the page's address. */
    private static final Set<String> TRACKING = Set.of(
            "aff_utm_source", "aff_utm_medium", "aff_utm_campaign", "aff_utm_content", "aff_utm_term");
    private static final int TRACKING_MAX_LENGTH = 200;

    /**
     * LeadScout's documentation: redirect on these, never on ERROR or empty. EXISTS
     * redirects too when LeadScout gives a link (owner, 2026-11-24: the person goes to
     * the link LeadScout gave); with no link it is the "already applied" page.
     */
    private static final Set<String> SOLD_ONE_WAY_OR_ANOTHER = Set.of("SOLD", "CPF", "NOT_SOLD");
    private static final String EXISTS = "EXISTS";

    /** A send this old was cut off (a restart, a crash), so the person may press the button again. */
    private static final Duration SENDING_TOO_LONG = Duration.ofMinutes(5);
    /** A retry needs at least this long to be worth it; with less left, the first answer stands. */
    private static final Duration RETRY_AT_LEAST = Duration.ofSeconds(10);
    /** Kept back from a retry's wait for reaching LeadScout (the 5 s connect limit). */
    private static final Duration CONNECT_RESERVE = Duration.ofSeconds(5);

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final LoanApplicationRepository applications;
    private final ClientRepository clients;
    private final ApplicationEventRepository events;
    private final BrandRepository brands;
    private final EncryptionService encryption;
    private final LeadScoutClient leadScout;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final ChannelService channels;
    private final long retryDelayMs;
    private final SecureRandom random = new SecureRandom();

    public ApplicationService(LoanApplicationRepository applications,
                              ClientRepository clients,
                              ApplicationEventRepository events,
                              BrandRepository brands,
                              EncryptionService encryption,
                              LeadScoutClient leadScout,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager,
                              ChannelService channels,
                              @Value("${app.leadscout.retry-delay-ms:1000}") long retryDelayMs) {
        this.applications = applications;
        this.clients = clients;
        this.events = events;
        this.brands = brands;
        this.encryption = encryption;
        this.leadScout = leadScout;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.channels = channels;
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * Saves step 1: a new application, or the same one when the person came back
     * with Previous — or when the same email fills step 1 again on the same brand
     * while an unfinished (abandoned) application is open, however long ago: one
     * abandoned application per email and brand, not one per try (owner, 2026-11-17).
     * The open one gets the newest answers but keeps its first channel and tracking,
     * and its created date. After a lead or a failure, the next try is a new one.
     */
    public String saveStepOne(ApplyDtos.StepOne form, String ip) {
        return transaction.execute(status -> {
            Brand brand = brandOf(form.host());
            Client client = clientOf(form.email());

            LoanApplication open = form.stepToken() == null ? null
                    : applications.lockByStepToken(form.stepToken())
                            .filter(a -> a.getStatus() == ApplicationStatus.ABANDONED)
                            .orElse(null);
            if (open == null) {
                open = applications.lockOpen(client, brand, ApplicationStatus.ABANDONED)
                        .stream().findFirst().orElse(null);
            }

            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("data", form.answers());
            // Where they first came from stays: a later visit from SEO does not undo the Facebook ad.
            stored.put("extra", open == null ? tracking(form.extra()) : asObject(decrypt(open.getAnswers()).get("extra")));
            stored.put("ip", ip);
            stored.put("userAgent", form.userAgent() == null ? "" : form.userAgent());

            if (open == null) {
                open = LoanApplication.builder()
                        .publicId(PublicIds.generate("AP"))
                        .stepToken(newToken())
                        .brand(brand)
                        .status(ApplicationStatus.ABANDONED)
                        .mode(leadScout.mode())
                        .consentAt(Instant.now())
                        .channel(ChannelService.of(form.extra()))
                        .build();
            }
            Client before = open.getClient();
            open.setClient(client);
            open.setRequestedAmount(form.requestedAmount());
            open.setLanguage(form.language());
            open.setAnswers(encrypt(stored));
            applications.saveAndFlush(open);
            // Every save counts as a try, with this visit's own channel.
            channels.recordStepOne(open.getBrand().getId(), open.getId(), ChannelService.of(form.extra()), leadScout.mode());

            // A corrected email should not leave an empty person behind.
            if (before != null && !before.getId().equals(client.getId()) && !applications.existsByClient(before)) {
                clients.delete(before);
            }
            return open.getStepToken();
        });
    }

    /** Saves step 2, sends the lead, and says what the form should show next. */
    public StepTwoResult finish(String token, ApplyDtos.StepTwo form) {
        Sending sending = transaction.execute(status -> startSending(token, form));
        if (sending.done != null) return sending.done;

        // Owner, 2026-11-20: one step 2 waits about 45 s in all, so the form's server
        // (55 s, Vercel 60 s) always gets the answer. The one safe retry happens only
        // when enough time is left, and it gets only what is left.
        Instant started = Instant.now();
        LeadScoutClient.Answer answer = leadScout.send(sending.body, 1);
        List<LeadScoutClient.Answer> answers = new ArrayList<>(List.of(answer));
        if (answer.tryAgain()) {
            pause();
            Duration left = leadScout.readTimeout().minus(Duration.between(started, Instant.now())).minus(CONNECT_RESERVE);
            if (left.compareTo(RETRY_AT_LEAST) >= 0) {
                answer = leadScout.send(sending.body, 2, left);
                answers.add(answer);
            } else {
                log.warn("No time left to send {} to LeadScout again", sending.publicId());
            }
        }

        if (answer.timedOut()) {
            // The public id only: no name, email or phone in the log.
            log.warn("LeadScout gave no answer within {}s for {}", leadScout.readTimeout().toSeconds(), sending.publicId());
        }
        LeadScoutClient.Answer last = answer;
        return transaction.execute(status -> saveAnswer(sending.applicationId, sending.body, answers, last));
    }

    private record Sending(Long applicationId, String publicId, Map<String, Object> body, StepTwoResult done) {
    }

    private Sending startSending(String token, ApplyDtos.StepTwo form) {
        LoanApplication application = applications.lockByStepToken(token == null ? "" : token)
                .orElseThrow(() -> ApiException.notFound("This application was not found. Please start again."));

        // A second click after the answer: the same answer, and no second send.
        if (application.getStatus() == ApplicationStatus.LEAD) {
            return new Sending(application.getId(), application.getPublicId(), null, resultFor(application));
        }
        if (application.getStatus() == ApplicationStatus.SENDING
                && application.getUpdatedAt().isAfter(Instant.now().minus(SENDING_TOO_LONG))) {
            throw ApiException.conflict("We are still working on your application. Please wait a moment.");
        }

        Map<String, Object> stored = decrypt(application.getAnswers());
        Map<String, Object> data = new LinkedHashMap<>(asObject(stored.get("data")));
        data.putAll(form.answers());
        stored.put("data", data);

        application.setAnswers(encrypt(stored));
        application.setStatus(ApplicationStatus.SENDING);
        application.setMode(leadScout.mode());
        applications.save(application);

        // Owner, 2026-11-17: LeadScout learns nothing about our channels. Its "Source"
        // (aff_utm_source) is always "API", to tell our form's leads from its white
        // label's; no other utm value and no affiliate_sub3 are sent. The LMS keeps the
        // visitor's own utm values and channel for itself (stored "extra", channel).
        // Owner, 2026-11-18: sub1 is the brand domain, sub2 is our application id.
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("aff_utm_source", "API");
        extra.put("affiliate_sub1", application.getBrand().getDomain());
        extra.put("affiliate_sub2", application.getPublicId());

        Map<String, Object> body = leadScout.body(data, extra, application.getLanguage(),
                String.valueOf(stored.get("ip")), String.valueOf(stored.get("userAgent")));
        return new Sending(application.getId(), application.getPublicId(), body, null);
    }

    private StepTwoResult saveAnswer(Long applicationId, Map<String, Object> body,
                                     List<LeadScoutClient.Answer> answers, LeadScoutClient.Answer last) {
        LoanApplication application = applications.findById(applicationId).orElseThrow();
        String request = encrypt(body);
        for (LeadScoutClient.Answer answer : answers) {
            events.save(ApplicationEvent.builder()
                    .application(application)
                    .mode(leadScout.mode())
                    .request(request)
                    .response(answer.body() == null ? null : encryption.encrypt(answer.body()))
                    .httpStatus(answer.httpStatus())
                    .leadscoutStatus(answer.status())
                    .problem(cut(answer.problem()))
                    .durationMs(answer.durationMs())
                    .build());
        }

        boolean exists = EXISTS.equals(last.status());
        boolean sold = last.status() != null && SOLD_ONE_WAY_OR_ANOTHER.contains(last.status());
        String link = sold || exists ? safeLink(last.redirectUrl()) : null;

        application.setLeadscoutStatus(last.status());
        application.setRedirectUrl(link);
        if (exists || link != null) {
            application.setStatus(ApplicationStatus.LEAD);
            applications.moveOthers(application.getClient(), application.getId(),
                    ApplicationStatus.ABANDONED, ApplicationStatus.RETURNED, Instant.now());
        } else {
            if (sold) log.error("LeadScout answered {} with no usable link for {}", last.status(), application.getPublicId());
            application.setStatus(ApplicationStatus.FAILED);
        }
        applications.save(application);
        channels.recordStepTwo(application.getBrand().getId(), application.getId(), application.getChannel(),
                leadScout.mode(), link != null);
        return resultFor(application);
    }

    private static StepTwoResult resultFor(LoanApplication application) {
        if (application.getStatus() == ApplicationStatus.LEAD && application.getRedirectUrl() != null) {
            return new StepTwoResult(StepTwoResult.GO_TO_LINK, application.getRedirectUrl());
        }
        return application.getStatus() == ApplicationStatus.LEAD
                ? new StepTwoResult(StepTwoResult.ALREADY_APPLIED, null)
                : new StepTwoResult(StepTwoResult.TRY_LATER, null);
    }

    /** "apply.ask4loan.ca" is the brand on "ask4loan.ca". Switched-off brands take no applications. */
    /** Someone opened the form. A website that is not a brand is not counted. */
    public void recordVisit(ApplyDtos.Visit visit, LocalDate day) {
        activeBrand(visit.host()).ifPresent(brand -> channels.recordVisit(brand.getId(), day, visit.visitorId(), visit.source()));
    }

    private Brand brandOf(String host) {
        return activeBrand(host)
                .orElseThrow(() -> ApiException.badRequest("This website is not set up to take applications."));
    }

    /** apply.365loan.ca → the brand 365loan.ca, if it is switched on. */
    private Optional<Brand> activeBrand(String host) {
        String name = host.trim().toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        while (name.contains(".")) {
            var brand = brands.findByDomain(name);
            if (brand.isPresent()) return brand.filter(Brand::isActive);
            name = name.substring(name.indexOf('.') + 1);
        }
        return Optional.empty();
    }

    private Client clientOf(String email) {
        String clean = email.trim().toLowerCase(Locale.ROOT);
        clients.addIfNew(PublicIds.generate("CL"), clean);
        return clients.findByEmail(clean).orElseThrow();
    }

    private static Map<String, String> tracking(Map<String, String> extra) {
        Map<String, String> kept = new LinkedHashMap<>();
        if (extra == null) return kept;
        extra.forEach((key, value) -> {
            if (TRACKING.contains(key) && value != null && !value.isBlank()) {
                kept.put(key, value.length() > TRACKING_MAX_LENGTH ? value.substring(0, TRACKING_MAX_LENGTH) : value);
            }
        });
        return kept;
    }

    /** The form sends people to this link, so only a real https address gets through. */
    private static String safeLink(String link) {
        if (link == null || link.isBlank() || link.length() > 2000) return null;
        try {
            URI uri = URI.create(link.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? link.trim() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encrypt(Map<String, Object> value) {
        return encryption.encrypt(objectMapper.writeValueAsString(value));
    }

    private Map<String, Object> decrypt(String value) {
        return objectMapper.readValue(encryption.decrypt(value), JSON_OBJECT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String cut(String text) {
        return text != null && text.length() > 500 ? text.substring(0, 500) : text;
    }

    private void pause() {
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
