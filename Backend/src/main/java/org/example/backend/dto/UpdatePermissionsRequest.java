package org.example.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Somebody's per-person exceptions, replacing whatever they had.
 *
 * <p><b>Differences from the role, not a copy of everything.</b> That is what the
 * table stores and it is the reason changing a role later still works: send the
 * whole effective set instead and "Nadia is a Member who was also given exports"
 * collapses into a frozen list that no longer moves when Member does.
 *
 * <p>Both lists are replaced wholesale. A missing permission means "no exception
 * for this one", which is the only reading that lets an exception be removed —
 * with a partial update there would be no way to say "stop overriding this".
 *
 * @param granted things this person has that their role does not give
 * @param denied  things their role gives that this person must not have
 */
public record UpdatePermissionsRequest(

        @NotNull(message = "granted is required — send an empty list for none")
        List<String> granted,

        @NotNull(message = "denied is required — send an empty list for none")
        List<String> denied,

        /** Optional note stored against every row written, for the audit later. */
        String reason
) {
}
