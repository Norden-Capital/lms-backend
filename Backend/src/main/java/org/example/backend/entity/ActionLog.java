package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One line of the action log: who did what to whom, and when. Written once and
 * never changed — there are no setters on purpose.
 */
@Entity
@Table(name = "action_logs")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private ActionType action;

    /** Null for things nobody signed in did — a failed sign-in, a "forgot password". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;

    /** "Name &lt;email&gt;" as it was at the time. */
    @Column(name = "actor_label", length = 400, updatable = false)
    private String actorLabel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", updatable = false)
    private User target;

    @Column(name = "target_label", length = 400, updatable = false)
    private String targetLabel;

    @Column(length = 500, updatable = false)
    private String details;

    @Column(length = 64, updatable = false)
    private String ip;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
