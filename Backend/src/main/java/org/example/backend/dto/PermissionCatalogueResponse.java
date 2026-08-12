package org.example.backend.dto;

import java.util.List;

/**
 * The whole permission model, grouped exactly as the sidebar groups it.
 *
 * <p>This is what lets the frontend delete its own copy. Until now
 * {@code lib/permissions.ts} described the modules, their labels, their order and
 * their actions by hand, because nothing exposed any of it — so adding a module
 * meant editing a migration and a TypeScript file and hoping the two agreed.
 *
 * <p>Nested rather than flat, because the shape the screens draw is nested. A
 * flat list of twenty-one strings would have every caller regrouping it, and each
 * of them would pick their own order.
 */
public record PermissionCatalogueResponse(List<Group> groups) {

    /** A sidebar section — Overview, Customers, Management. */
    public record Group(String key, String label, List<Module> modules) {
    }

    /**
     * One module and everything that may be done to it.
     *
     * @param adminOnly permissions here can only ever come from being an
     *                  administrator. The picker must not offer them to a member,
     *                  and the API refuses to grant them individually.
     * @param actions   in the order they should be shown, read first — it is the
     *                  one every other action depends on
     */
    public record Module(String key,
                         String label,
                         String description,
                         boolean adminOnly,
                         List<String> actions) {
    }
}
