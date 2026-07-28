package org.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One individual action the system can allow, stored as a resource x action pair
 * (RECORD + CREATE) rather than a single flat name.
 *
 * <p>That split is what lets an admin screen render itself: query the table,
 * group by resource, and every group is a row of checkboxes.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What is being acted on: RECORD, USER, REPORT. */
    @Column(nullable = false, length = 50)
    private String resource;

    /** What may be done to it: CREATE, READ, UPDATE, DELETE, EXPORT. */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 255)
    private String description;

    /**
     * The form this permission takes inside a JWT and in {@code @PreAuthorize}
     * checks, e.g. {@code RECORD:CREATE}.
     */
    public String toAuthority() {
        return resource + ":" + action;
    }
}
