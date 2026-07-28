package org.example.backend.entity;

/**
 * Where an account sits in its lifecycle. A plain boolean could not tell
 * "hasn't confirmed their email yet" apart from "banned".
 */
public enum UserStatus {

    /** Registered but the email link hasn't been clicked yet. Cannot log in. */
    PENDING_VERIFICATION,

    /** Normal, working account. */
    ACTIVE,

    /** Temporarily blocked by an admin. Cannot log in. */
    SUSPENDED,

    /** Soft-deleted. The row stays so course history survives. Cannot log in. */
    DEACTIVATED;

    public boolean canLogIn() {
        return this == ACTIVE;
    }
}
