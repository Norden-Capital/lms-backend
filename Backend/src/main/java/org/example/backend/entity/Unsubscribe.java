package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Someone who asked a brand for no more emails — see migration V21. One row per
 * email and brand. Written only by {@code UnsubscribeService.save(...)}, a
 * single INSERT … ON CONFLICT, so this class is read-only.
 */
@Entity
@Table(name = "unsubscribes")
@Getter
@NoArgsConstructor
public class Unsubscribe {

    /** The reasons the page offers. None is also allowed. */
    public enum Reason { TOO_MANY_EMAILS, NOT_INTERESTED, ALREADY_GOT_LOAN, NEVER_SIGNED_UP, OTHER }

    @Id
    private Long id;

    /** "UN-7K4F-92QX" — what the API uses. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;

    /** Trimmed and in lower case. */
    @Column(nullable = false)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    /** The person, when they had applied before unsubscribing. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Reason reason;

    @Column(name = "other_text")
    private String otherText;

    /** Encrypted. */
    @Column(columnDefinition = "TEXT")
    private String ip;

    /** The first time. */
    @Column(name = "created_at")
    private Instant createdAt;

    /** The last time — the date the lists show and filter on. */
    @Column(name = "updated_at")
    private Instant updatedAt;
}
