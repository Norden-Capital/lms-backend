package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A connected Gmail mailbox — see migration V11. The mail itself lives in
 * Gmail and is read live; this is only how to reach it.
 */
@Entity
@Table(name = "mail_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "MB-7K4F-92QX" — what the API and the address bar use. */
    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    private String publicId;

    /** The name shown in the app and on sent mail — "Norden Capital". */
    @Column(nullable = false, length = 80)
    private String label;

    @Column(nullable = false, unique = true, length = 255, updatable = false)
    private String email;

    /** Which business it belongs to, for the brand filter. */
    @Column(nullable = false, length = 80)
    private String brand;

    @Column(nullable = false, length = 20)
    private String provider;

    /** The Google App Password, encrypted. Never leaves the backend. */
    @Column(name = "password_encrypted", nullable = false, length = 500)
    private String passwordEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
