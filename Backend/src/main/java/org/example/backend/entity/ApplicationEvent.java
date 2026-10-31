package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One send to LeadScout and what came back — see migration V17. */
@Entity
@Table(name = "application_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private LoanApplication application;

    @Column(nullable = false, length = 10)
    private String mode;

    /** Encrypted: what was sent. */
    @Column(columnDefinition = "TEXT")
    private String request;

    /** Encrypted: what came back. */
    @Column(columnDefinition = "TEXT")
    private String response;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "leadscout_status", length = 20)
    private String leadscoutStatus;

    /** What went wrong, in words, when something did. */
    @Column(length = 500)
    private String problem;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
