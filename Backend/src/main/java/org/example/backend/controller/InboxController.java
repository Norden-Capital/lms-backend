package org.example.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.backend.dto.MailDtos;
import org.example.backend.security.AuthPrincipal;
import org.example.backend.service.GmailClient;
import org.example.backend.service.InboxService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The inbox — connected Gmail mailboxes, read and answered live.
 *
 * <p>Each verb is its own permission (INBOX:READ, SEND, ARCHIVE, DELETE, STAR),
 * so a member can be given reading without sending. Connecting a mailbox is
 * the Owner's alone. Every message call names the mailbox it is about.
 */
@RestController
@RequestMapping("/api/inbox")
@Tag(name = "Inbox", description = "Connected Gmail mailboxes")
@SecurityRequirement(name = "BearerAuth")
public class InboxController {

    private final InboxService inbox;
    private final GmailClient gmail;

    public InboxController(InboxService inbox, GmailClient gmail) {
        this.inbox = inbox;
        this.gmail = gmail;
    }

    // ── Mailboxes ───────────────────────────────────────────────────────────

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('INBOX:READ')")
    @Operation(summary = "The connected mailboxes")
    public List<MailDtos.Account> accounts() {
        return inbox.list();
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Connect a Gmail mailbox with its App Password — the Owner only",
               description = "Signs in to Gmail first; a wrong App Password is refused with 400.")
    public ResponseEntity<MailDtos.Account> connect(@Valid @RequestBody MailDtos.ConnectAccount request,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inbox.connect(request, principal.userId()));
    }

    @DeleteMapping("/accounts/{account}")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Disconnect a mailbox — the Owner only. Nothing in Gmail is touched.")
    public ResponseEntity<Void> disconnect(@PathVariable String account,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        inbox.disconnect(account, principal.userId());
        return ResponseEntity.noContent().build();
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @GetMapping("/{account}/folders")
    @PreAuthorize("hasAuthority('INBOX:READ')")
    @Operation(summary = "The folders, with unread counts for the Inbox and Junk")
    public List<MailDtos.Folder> folders(@PathVariable String account) {
        return gmail.folders(inbox.credentials(account));
    }

    @GetMapping("/{account}/messages")
    @PreAuthorize("hasAuthority('INBOX:READ')")
    @Operation(summary = "One page of a folder, newest first",
               description = "`q` is Gmail's own search — anything that works in Gmail's search box works here.")
    public MailDtos.Page messages(@PathVariable String account,
                                  @RequestParam(defaultValue = "inbox") String folder,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "false") boolean unread,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "25") int size) {
        return gmail.list(inbox.credentials(account), folder, q, unread, page, size);
    }

    @GetMapping("/{account}/messages/{mail}")
    @PreAuthorize("hasAuthority('INBOX:READ')")
    @Operation(summary = "One message, open — marks it as read in Gmail")
    public MailDtos.Message message(@PathVariable String account, @PathVariable String mail) {
        return gmail.open(inbox.credentials(account), mail);
    }

    @PostMapping("/{account}/messages/{mail}/attachments/{index}/link")
    @PreAuthorize("hasAuthority('INBOX:READ')")
    @Operation(summary = "A two-minute download link for one attached file")
    public MailDtos.Link attachmentLink(@PathVariable String account, @PathVariable String mail,
                                        @PathVariable int index) {
        return new MailDtos.Link(inbox.attachmentLink(account, mail, index));
    }

    @GetMapping("/attachment")
    @Operation(summary = "Download an attached file by its two-minute link",
               description = "Needs no sign-in: the link itself is the proof. Ask for one with .../link.")
    public ResponseEntity<byte[]> attachment(@RequestParam String token) {
        GmailClient.AttachmentData file = inbox.attachment(token);
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.contentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }

    // ── Organising ──────────────────────────────────────────────────────────

    @PostMapping("/{account}/messages/{mail}/actions")
    @Operation(summary = "Archive, move to the Inbox, junk, delete, star or mark read",
               description = """
                       `action` is one of archive, inbox, junk, delete, star, unstar, read, unread.
                       Each needs its own permission: archive, inbox and junk need INBOX:ARCHIVE;
                       delete needs INBOX:DELETE; star and unstar need INBOX:STAR; read and unread
                       need only INBOX:READ. Delete in Trash deletes for good.""")
    @PreAuthorize("""
            hasAuthority('INBOX:READ') and (
              (#request.action() == 'archive' or #request.action() == 'inbox' or #request.action() == 'junk')
                ? hasAuthority('INBOX:ARCHIVE')
              : #request.action() == 'delete' ? hasAuthority('INBOX:DELETE')
              : (#request.action() == 'star' or #request.action() == 'unstar') ? hasAuthority('INBOX:STAR')
              : true)""")
    public ResponseEntity<Void> act(@PathVariable String account, @PathVariable String mail,
                                    @Valid @RequestBody MailDtos.Action request) {
        gmail.act(inbox.credentials(account), mail, request.action());
        return ResponseEntity.noContent().build();
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    @PostMapping("/{account}/send-link")
    @PreAuthorize("hasAuthority('INBOX:SEND')")
    @Operation(summary = "A two-minute pass to send one email from this mailbox",
               description = "The browser then posts the email to /api/inbox/send with it.")
    public MailDtos.Link sendLink(@PathVariable String account) {
        return new MailDtos.Link(inbox.sendLink(account));
    }

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Send an email through Gmail, or save it as a draft",
               description = """
                       Needs no sign-in: the pass from .../send-link is the proof, and only someone
                       holding INBOX:SEND can get one. Multipart, so files can be attached (25 MB in
                       all, Gmail's limit). `mode` is new, reply, replyAll or forward; for the last
                       three `original` is the id of the message being answered — replies are
                       threaded in Gmail, and a forward carries the original's files. `draft=true`
                       saves to Drafts instead of sending.""")
    public ResponseEntity<Void> send(@RequestParam String token,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String cc,
                                     @RequestParam(required = false) String bcc,
                                     @RequestParam(required = false) String subject,
                                     @RequestParam(required = false) String body,
                                     @RequestParam(defaultValue = "new") String mode,
                                     @RequestParam(required = false) String original,
                                     @RequestParam(defaultValue = "false") boolean draft,
                                     @RequestParam(required = false) List<MultipartFile> files) {
        gmail.send(inbox.sendCredentials(token),
                new GmailClient.Outgoing(to, cc, bcc, subject, body, mode, original, files), draft);
        return ResponseEntity.noContent().build();
    }
}
