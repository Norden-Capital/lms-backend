package org.example.backend.entity;

/**
 * Whether a per-user exception adds a permission the role lacks, or removes one
 * the role includes.
 *
 * <p>DENY always wins over GRANT, and both win over the role. That single rule is
 * what keeps the permission system explainable.
 */
public enum PermissionEffect {
    GRANT,
    DENY
}
