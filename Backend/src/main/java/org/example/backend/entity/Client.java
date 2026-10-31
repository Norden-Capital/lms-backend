package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A person who applied, known by their email — see migration V17. Their answers
 * live on each {@link LoanApplication}, so nothing an earlier apply said is lost.
 */
@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "CL-7K4F-92QX" — what the API uses. */
    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    private String publicId;

    /** Trimmed and in lower case, so one person is never two rows. */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
