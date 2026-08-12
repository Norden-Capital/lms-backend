package org.example.backend.repository;

import jakarta.persistence.criteria.Predicate;
import org.example.backend.entity.User;
import org.example.backend.entity.UserStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * The filters behind the user list, assembled into one query.
 *
 * <p><b>The point is that this runs in the database.</b> Fetching every account
 * and then filtering in Java would make the paging decorative — the cost is
 * already paid by the time the first row is discarded, and "page 3 of the
 * administrators" would be page 3 of everyone with the non-administrators
 * removed, which is not the same list.
 *
 * <p>Each filter is skipped when its parameter is absent, so the same method
 * serves the unfiltered list and every combination of the four.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /**
     * @param search      matched against first name, last name and email
     * @param roleName    exact, e.g. {@code ADMIN}
     * @param status      exact
     * @param mfaEnabled  whether two-factor is on
     */
    public static Specification<User> matching(String search,
                                               String roleName,
                                               UserStatus status,
                                               Boolean mfaEnabled) {

        return (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();

            if (hasText(search)) {
                // One box, three columns. Somebody typing "nadia" does not know
                // or care whether they are searching a first name or an email,
                // and asking them to choose is a filter nobody uses.
                String pattern = "%" + search.trim().toLowerCase() + "%";

                conditions.add(builder.or(
                        builder.like(builder.lower(root.get("firstName")), pattern),
                        builder.like(builder.lower(root.get("lastName")), pattern),
                        builder.like(builder.lower(root.get("email")), pattern)
                ));
            }

            if (hasText(roleName)) {
                conditions.add(builder.equal(root.get("role").get("name"), roleName.trim().toUpperCase()));
            }

            if (status != null) {
                conditions.add(builder.equal(root.get("status"), status));
            }

            if (mfaEnabled != null) {
                conditions.add(builder.equal(root.get("mfaEnabled"), mfaEnabled));
            }

            return conditions.isEmpty() ? null : builder.and(conditions.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
