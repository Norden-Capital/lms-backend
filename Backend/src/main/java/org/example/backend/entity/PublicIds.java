package org.example.backend.entity;

import java.security.SecureRandom;

/**
 * The public ID every account carries, like {@code NC-7K4F-92QX}.
 *
 * <p>Shown on screen and used in addresses instead of the numeric id, which
 * gave away how many accounts exist and invited trying the next number. See
 * migration V7 for the format.
 */
public final class PublicIds {

    /** No 0/O and no 1/I/L: a code can be read out loud or typed by hand without mistakes. */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicIds() {
    }

    public static String generate() {
        StringBuilder code = new StringBuilder("NC-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) code.append('-');
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
