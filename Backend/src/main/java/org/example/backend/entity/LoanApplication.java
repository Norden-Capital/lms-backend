package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One time a client applied, through one brand's form — see migration V17. */
@Entity
@Table(name = "loan_applications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "AP-7K4F-92QX" — shown in the LMS and sent to LeadScout. Opens nothing by itself. */
    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    private String publicId;

    /** Long and random: what the form holds to finish step 2. */
    @Column(name = "step_token", nullable = false, unique = true, length = 64, updatable = false)
    private String stepToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false, updatable = false)
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status;

    /** "fake", "test" or "live" when it was saved, so test applications are never counted as real. */
    @Column(nullable = false, length = 10)
    private String mode;

    @Column(name = "requested_amount", nullable = false)
    private int requestedAmount;

    @Column(nullable = false, length = 2)
    private String language;

    /** Encrypted JSON of everything the person answered. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String answers;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    /** LeadScout's last answer: SOLD, CPF, NOT_SOLD, EXISTS, ERROR. Null until it answers. */
    @Column(name = "leadscout_status", length = 20)
    private String leadscoutStatus;

    @Column(name = "redirect_url", length = 2000)
    private String redirectUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
