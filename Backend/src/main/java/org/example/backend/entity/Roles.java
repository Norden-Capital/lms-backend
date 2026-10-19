package org.example.backend.entity;

/**
 * The role names the code makes decisions on. The roles themselves live in the
 * database; these are the three whose names carry rules.
 */
public final class Roles {

    private Roles() {
    }

    /**
     * Exactly one account: the bootstrap one (migration V6, DatabaseSeeder). It
     * can do everything an administrator can, and deactivate administrators.
     * Nothing in the API can give this role to anybody, or take it away.
     */
    public static final String OWNER = "OWNER";

    public static final String ADMIN = "ADMIN";

    public static final String MEMBER = "MEMBER";

    /** Holds every permission by definition: an administrator or the Owner. */
    public static boolean grantsEverything(String role) {
        return ADMIN.equals(role) || OWNER.equals(role);
    }
}
