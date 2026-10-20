package org.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The one mailbox the inbox reads — decided 2026-10-20, the same way oneBase
 * does it: a single Gmail (Andrew's) that every brand address forwards into
 * through Cloudflare Email Routing. The brands themselves are on the
 * Configuration page (see BrandService).
 *
 * <p>Set on the server, never in the app: {@code INBOX_USERNAME} (the Gmail
 * address) and {@code INBOX_PASSWORD} (a Google App Password). Unset, the inbox
 * says "not set up" rather than failing — the rest of the app runs without it.
 */
@Component
public class InboxSettings implements GmailClient.Credentials {

    private static final Logger log = LoggerFactory.getLogger(InboxSettings.class);

    private final String username;
    private final String password;

    public InboxSettings(@Value("${app.inbox.username:}") String username,
                         @Value("${app.inbox.password:}") String password) {
        this.username = username.strip().toLowerCase(Locale.ROOT);
        // Google shows App Passwords in groups of four; the spaces are not part of it.
        this.password = password.replaceAll("\\s+", "");
        if (!connected()) log.warn("No inbox mailbox configured (INBOX_USERNAME / INBOX_PASSWORD) — the Inbox says \"not set up\".");
    }

    public boolean connected() {
        return !username.isEmpty() && !password.isEmpty();
    }

    /** One mailbox, so one connection — its id never changes. */
    @Override
    public Long id() {
        return 1L;
    }

    @Override
    public String email() {
        return username;
    }

    @Override
    public String password() {
        return password;
    }

    /** The name on mail sent as the mailbox itself — its address; brands have their own names. */
    @Override
    public String label() {
        return username;
    }
}
