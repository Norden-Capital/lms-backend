package org.example.backend.service;

import io.jsonwebtoken.JwtException;
import org.example.backend.dto.MailDtos;
import org.example.backend.entity.Brand;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.BrandRepository;
import org.example.backend.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inbox — decided 2026-10-20, the oneBase way: one Gmail mailbox, set on
 * the server ({@link InboxSettings}), that every brand address forwards into.
 * Nothing to connect in the app. The brands come from the Configuration page.
 * The mail itself is {@link GmailClient}'s.
 */
@Service
public class InboxService {

    private final InboxSettings settings;
    private final BrandRepository brands;
    private final GmailClient gmail;
    private final JwtService jwt;

    public InboxService(InboxSettings settings, BrandRepository brands, GmailClient gmail, JwtService jwt) {
        this.settings = settings;
        this.brands = brands;
        this.gmail = gmail;
        this.jwt = jwt;
    }

    /** The mailbox, or 503 when the server has none set up. */
    public GmailClient.Credentials mailbox() {
        if (!settings.connected()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The inbox is not set up yet. Add the Gmail login to the server settings.");
        }
        return settings;
    }

    private List<MailDtos.Sender> brandSenders() {
        // Switched-off brands are out of the filter and the From list.
        return brands.findAllByActiveTrueOrderByNameAscEmailAsc().stream()
                .map((Brand b) -> new MailDtos.Sender(b.getName(), b.getEmail(), b.getLogoUrl())).toList();
    }

    /** The brand addresses, for tagging and filtering mail. */
    public List<String> brandEmails() {
        return brandSenders().stream().map(MailDtos.Sender::email).toList();
    }

    /** Every address mail can go out from: the mailbox itself, then each brand. */
    private List<MailDtos.Sender> senders() {
        List<MailDtos.Sender> all = new ArrayList<>();
        all.add(new MailDtos.Sender(settings.label(), settings.email(), null));
        brandSenders().stream().filter(s -> !s.email().equals(settings.email())).forEach(all::add);
        return all;
    }

    public MailDtos.Overview overview() {
        GmailClient.Credentials box = mailbox();
        return new MailDtos.Overview(box.email(), box.label(), brandSenders(), senders(), gmail.folders(box));
    }

    /** The From of an outgoing email — only the mailbox or one of its brands. */
    public MailDtos.Sender sender(String email) {
        List<MailDtos.Sender> all = senders();
        if (email == null || email.isBlank()) return all.getFirst();
        String wanted = email.strip().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(s -> s.email().equals(wanted))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Choose one of the addresses in the From list."));
    }

    // ── Two-minute links ────────────────────────────────────────────────────

    /** A two-minute link to one attached file — the browser downloads it straight from here. */
    public String attachmentLink(String mailId, int index) {
        mailbox();
        return "/api/inbox/attachment?token="
                + jwt.generateDownloadToken(JwtService.TYPE_MAIL_ATTACHMENT, mailId + "|" + index);
    }

    public GmailClient.AttachmentData attachment(String token) {
        String[] parts;
        try {
            parts = jwt.parseAs(token, JwtService.TYPE_MAIL_ATTACHMENT).getSubject().split("\\|");
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("This link expired. Click the file again.");
        }
        if (parts.length != 2) throw ApiException.unauthorized("This download link is not valid.");
        return gmail.attachment(mailbox(), parts[0], Integer.parseInt(parts[1]));
    }

    /**
     * A two-minute pass to send one email, straight from the browser — the
     * website's server cannot carry 25 MB of attachments.
     */
    public String sendLink() {
        mailbox();
        return "/api/inbox/send?token=" + jwt.generateDownloadToken(JwtService.TYPE_MAIL_SEND, "send");
    }

    public void checkSendPass(String token) {
        try {
            jwt.parseAs(token, JwtService.TYPE_MAIL_SEND);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("This took too long to send. Press Send again.");
        }
    }
}
