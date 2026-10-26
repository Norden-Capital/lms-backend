package org.example.backend.entity;

/**
 * Everything the action log records. Stored by name, so the order here can
 * change freely; renaming one means a migration for the rows already written.
 */
public enum ActionType {

    // ── Accounts ──────────────────────────────────────────────────────────
    USER_INVITED,
    INVITATION_RESENT,
    INVITATION_ACCEPTED,
    INVITATION_CANCELLED,
    DETAILS_UPDATED,
    ROLE_CHANGED,
    PERMISSIONS_CHANGED,
    ACCOUNT_DEACTIVATED,
    ACCOUNT_REACTIVATED,

    // ── Passwords ─────────────────────────────────────────────────────────
    /** An administrator sent somebody a reset link. */
    PASSWORD_RESET_SENT,
    /** Somebody used "Forgot password?" on their own account. */
    PASSWORD_RESET_REQUESTED,
    /** A new password was chosen from a reset link. */
    PASSWORD_RESET_COMPLETED,

    // ── Two-factor ────────────────────────────────────────────────────────
    TWO_FACTOR_ENABLED,
    TWO_FACTOR_RESET,
    RECOVERY_CODE_USED,
    RECOVERY_CODES_REGENERATED,

    // ── Signing in ────────────────────────────────────────────────────────
    SIGN_IN,
    SIGN_IN_FAILED,
    ACCOUNT_LOCKED,
    /** Someone ended one or more of their own sessions from Settings. */
    SESSION_ENDED,
    /** Someone changed their own password from Settings. */
    PASSWORD_CHANGED,

    // ── Backups ───────────────────────────────────────────────────────────
    /** The Owner pressed "Back up now". The nightly run is not logged here. */
    BACKUP_STARTED,
    BACKUP_DOWNLOADED,
    BACKUP_DELETED,

    // ── Configuration ─────────────────────────────────────────────────────
    BRAND_ADDED,
    BRAND_UPDATED,
    BRAND_REMOVED
}
