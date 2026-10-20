package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A brand and the address its mail arrives at — see migration V11. Answers to
 * that mail go out from the same address.
 */
@Entity
@Table(name = "brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "BR-7K4F-92QX" — what the API uses. */
    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    private String publicId;

    @Column(nullable = false, length = 80)
    private String name;

    /** Lower case. Forwards into the inbox mailbox, and is in its "Send mail as" list. */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
