package org.example.backend.dto;

import java.util.List;

/**
 * A role and what it grants on its own, before anything is given to a person.
 *
 * @param permissions {@code RESOURCE:ACTION} strings, sorted so two calls agree
 */
public record RoleResponse(String name, String description, List<String> permissions) {
}
