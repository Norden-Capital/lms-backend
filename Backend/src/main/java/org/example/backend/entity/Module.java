package org.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One part of the application that permissions can be held over.
 *
 * <p>{@link Permission} is a {@code resource + action} pair, and the resource was
 * a bare string — nothing knew that {@code CUSTOMER} means "Customer List", that
 * it belongs under Customers, or where it sits in the menu. This carries that,
 * once per module rather than repeated across its five permission rows.
 *
 * <p>It exists so the frontend can stop describing the permission model in a
 * TypeScript file that has to be hand-edited whenever the database changes.
 */
@Entity
@Table(name = "modules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Module {

    /** Matches {@code permissions.resource}, e.g. {@code CUSTOMER}. */
    @Id
    @Column(name = "key", length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(length = 255)
    private String description;

    /** The sidebar section, e.g. {@code CUSTOMERS}. */
    @Column(name = "group_key", nullable = false, length = 50)
    private String groupKey;

    @Column(name = "group_label", nullable = false, length = 100)
    private String groupLabel;

    /** Menu order. Alphabetical is not how anybody reads these. */
    @Column(nullable = false)
    private int position;

    /**
     * Whether these permissions can only ever come from being an administrator.
     *
     * <p>Stronger than "the Member role does not include it". A module marked
     * here cannot be granted to a member individually either — the API refuses
     * rather than quietly ignoring the attempt, and the picker never offers it.
     *
     * <p>Management is the case this exists for: there is no such thing as a
     * member who can see the user list. If that is ever wanted, it is a third
     * role, not a permission.
     */
    @Column(name = "admin_only", nullable = false)
    private boolean adminOnly;
}
