package org.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An administrator setting somebody else's password for them.
 *
 * <p><b>Not the same thing as changing your own.</b> Changing your own password
 * must ask for the current one, because a browser left open would otherwise be
 * enough to take an account over permanently. This one cannot ask for it — the
 * administrator does not know it, and the entire point is that the person who
 * does has forgotten it.
 *
 * <p>The safeguard is therefore the permission rather than the old password:
 * {@code USER:UPDATE}, held by administrators only. That is a real difference in
 * kind, which is why the two live at different addresses instead of one endpoint
 * with an optional field.
 *
 * <p>Nothing is emailed — there is no mail server. The password is generated on
 * screen, copied, and handed over by whatever route the administrator trusts.
 */
public record AdminSetPasswordRequest(

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password
) {
}
