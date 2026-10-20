package org.example.backend.service;

import io.jsonwebtoken.JwtException;
import org.example.backend.dto.MailDtos;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.MailAccount;
import org.example.backend.entity.PublicIds;
import org.example.backend.exception.ApiException;
import org.example.backend.repository.MailAccountRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * The inbox — decided 2026-10-20. Which Gmail mailboxes are connected, and
 * the way in to each. The mail itself is {@link GmailClient}'s.
 *
 * <p>Connecting and disconnecting a mailbox is the Owner's alone; reading and
 * answering mail follows the INBOX:* permissions.
 */
@Service
public class InboxService {

    private final MailAccountRepository accounts;
    private final UserRepository users;
    private final EncryptionService encryption;
    private final GmailClient gmail;
    private final ActionLogService actionLog;
    private final JwtService jwt;

    public InboxService(MailAccountRepository accounts, UserRepository users, EncryptionService encryption,
                        GmailClient gmail, ActionLogService actionLog, JwtService jwt) {
        this.accounts = accounts;
        this.users = users;
        this.encryption = encryption;
        this.gmail = gmail;
        this.actionLog = actionLog;
        this.jwt = jwt;
    }

    /** What {@link GmailClient} needs, with the password decrypted only for the call. */
    private record Credentials(Long id, String email, String password, String label)
            implements GmailClient.Credentials {
    }

    @Transactional(readOnly = true)
    public GmailClient.Credentials credentials(String publicId) {
        MailAccount account = find(publicId);
        return new Credentials(account.getId(), account.getEmail(),
                encryption.decrypt(account.getPasswordEncrypted()), account.getLabel());
    }

    private MailAccount find(String publicId) {
        return accounts.findByPublicId(publicId == null ? "" : publicId)
                .orElseThrow(() -> ApiException.notFound("No such mailbox."));
    }

    @Transactional(readOnly = true)
    public List<MailDtos.Account> list() {
        return accounts.findAllByOrderByBrandAscLabelAsc().stream().map(InboxService::toDto).toList();
    }

    private static MailDtos.Account toDto(MailAccount a) {
        return new MailDtos.Account(a.getPublicId(), a.getLabel(), a.getEmail(), a.getBrand(), a.getProvider());
    }

    /** Signs in to Gmail first, so a wrong App Password is caught now, not later. */
    @Transactional
    public MailDtos.Account connect(MailDtos.ConnectAccount request, Long actorId) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (!email.endsWith("@gmail.com") && !email.endsWith("@googlemail.com")) {
            throw ApiException.badRequest("Only Gmail addresses (@gmail.com) can be connected for now.");
        }
        if (accounts.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("This mailbox is already connected.");
        }
        // Google shows the App Password in groups of four; the spaces are not part of it.
        String password = request.appPassword().replaceAll("\\s+", "");
        gmail.verify(email, password);

        MailAccount account = accounts.save(MailAccount.builder()
                .publicId(PublicIds.generate("MB"))
                .label(request.label().strip())
                .email(email)
                .brand(request.brand().strip())
                .provider("gmail")
                .passwordEncrypted(encryption.encrypt(password))
                .createdBy(actorId == null ? null : users.findById(actorId).orElse(null))
                .build());
        actionLog.record(ActionType.MAILBOX_CONNECTED, actorId, null, account.getLabel() + " <" + email + ">");
        return toDto(account);
    }

    /** Forgets the mailbox here. Nothing in Gmail is touched. */
    @Transactional
    public void disconnect(String publicId, Long actorId) {
        MailAccount account = find(publicId);
        accounts.delete(account);
        gmail.forget(account.getId());
        actionLog.record(ActionType.MAILBOX_DISCONNECTED, actorId, null,
                account.getLabel() + " <" + account.getEmail() + ">");
    }

    // ── Attachment links ───────────────────────────────────────────────────

    /** A two-minute link to one attached file — the browser downloads it straight from here. */
    @Transactional(readOnly = true)
    public String attachmentLink(String accountId, String mailId, int index) {
        find(accountId);
        return "/api/inbox/attachment?token="
                + jwt.generateDownloadToken(JwtService.TYPE_MAIL_ATTACHMENT, accountId + "|" + mailId + "|" + index);
    }

    /**
     * A two-minute pass to send one email from this mailbox, straight from the
     * browser — the website's server cannot carry 25 MB of attachments.
     */
    @Transactional(readOnly = true)
    public String sendLink(String accountId) {
        find(accountId);
        return "/api/inbox/send?token=" + jwt.generateDownloadToken(JwtService.TYPE_MAIL_SEND, accountId);
    }

    /** The mailbox a send pass is for. */
    public GmailClient.Credentials sendCredentials(String token) {
        try {
            return credentials(jwt.parseAs(token, JwtService.TYPE_MAIL_SEND).getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("This took too long to send. Press Send again.");
        }
    }

    public GmailClient.AttachmentData attachment(String token) {
        String[] parts;
        try {
            parts = jwt.parseAs(token, JwtService.TYPE_MAIL_ATTACHMENT).getSubject().split("\\|");
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("This download link has expired. Go back and click the file again.");
        }
        if (parts.length != 3) throw ApiException.unauthorized("This download link is not valid.");
        return gmail.attachment(credentials(parts[0]), parts[1], Integer.parseInt(parts[2]));
    }
}
