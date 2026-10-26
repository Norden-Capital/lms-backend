package org.example.backend.service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.SearchTerm;
import org.eclipse.angus.mail.gimap.GmailFolder;
import org.eclipse.angus.mail.gimap.GmailMessage;
import org.eclipse.angus.mail.gimap.GmailRawSearchTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.example.backend.dto.MailDtos;
import org.example.backend.exception.ApiException;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to Gmail — decided 2026-10-20.
 *
 * <p>Reading, searching and organising go over IMAP (imap.gmail.com), using
 * Gmail's own extensions for search and labels. Sending goes over SMTP
 * (smtp.gmail.com), and Gmail files a copy in Sent by itself. Both sign in
 * with the mailbox's Google App Password.
 *
 * <p>Nothing is copied into the database: every list and every message is read
 * live, so what the app shows is what Gmail has. One IMAP connection is kept
 * open per mailbox and reused; all work on a mailbox goes through it one call
 * at a time.
 *
 * <p>A message is named "folder.uid" — Gmail's id for it inside that folder.
 */
@Service
public class GmailClient {

    private static final Logger log = LoggerFactory.getLogger(GmailClient.class);

    private static final String IMAP_HOST = "imap.gmail.com";
    private static final String SMTP_HOST = "smtp.gmail.com";

    /** The folders the inbox shows, in order, and how Gmail marks each one. */
    private record FolderDef(String id, String label, String icon, String group, String specialUse) {
    }

    private static final List<FolderDef> FOLDERS = List.of(
            new FolderDef("inbox", "Inbox", "inbox", "working", null),
            new FolderDef("drafts", "Drafts", "file", "working", "\\Drafts"),
            new FolderDef("sent", "Sent", "send", "working", "\\Sent"),
            new FolderDef("junk", "Junk", "archive-x", "aside", "\\Junk"),
            new FolderDef("trash", "Trash", "trash", "aside", "\\Trash"),
            new FolderDef("archive", "Archive", "archive", "aside", "\\All"));

    /** Short previews, kept because a message never changes once it has arrived. */
    private static final int PREVIEW_CACHE = 5000;
    private static final int PREVIEW_LENGTH = 220;

    /** A body part bigger than this is not read just to make a preview. */
    private static final int PREVIEW_MAX_PART = 300_000;

    public interface Credentials {
        Long id();

        String email();

        String password();

        String label();
    }

    private record Connection(Store store, Map<String, String> folderNames) {
    }

    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, String> previews = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > PREVIEW_CACHE;
                }
            });

    // ── Connecting ──────────────────────────────────────────────────────────

    private static Session imapSession() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "gimaps");
        props.put("mail.gimaps.connectiontimeout", "15000");
        props.put("mail.gimaps.timeout", "30000");
        // Reading a message to preview it must not mark it as read in Gmail.
        props.put("mail.gimaps.peek", "true");
        props.put("mail.mime.decodefilename", "true");
        return Session.getInstance(props);
    }

    private static Session smtpSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "60000");
        return Session.getInstance(props);
    }

    /** Drops the open connection — after a mailbox is removed, say. */
    public void forget(Long accountId) {
        Connection connection = connections.remove(accountId);
        if (connection != null) closeQuietly(connection.store());
    }

    @PreDestroy
    void closeAll() {
        connections.values().forEach(c -> closeQuietly(c.store()));
        connections.clear();
    }

    private static void closeQuietly(Store store) {
        try {
            store.close();
        } catch (MessagingException ignored) {
            // Already gone.
        }
    }

    private Connection connection(Credentials account) throws MessagingException {
        Connection existing = connections.get(account.id());
        if (existing != null && existing.store().isConnected()) return existing;
        if (existing != null) closeQuietly(existing.store());

        Store store = imapSession().getStore("gimaps");
        store.connect(IMAP_HOST, account.email(), account.password());

        // Gmail names its folders in the account's language ("[Gmail]/Messages
        // envoyés"), so they are found by what they are, not what they are called.
        Map<String, String> names = new HashMap<>();
        names.put("inbox", "INBOX");
        for (Folder folder : store.getDefaultFolder().list("*")) {
            if (!(folder instanceof IMAPFolder imap)) continue;
            Set<String> attributes = new HashSet<>(Arrays.asList(imap.getAttributes()));
            for (FolderDef def : FOLDERS) {
                if (def.specialUse() != null && attributes.contains(def.specialUse())) {
                    names.put(def.id(), folder.getFullName());
                }
            }
        }

        Connection connection = new Connection(store, names);
        connections.put(account.id(), connection);
        return connection;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws MessagingException, IOException;
    }

    /**
     * Runs one piece of work on a mailbox, one at a time, reconnecting once if
     * Gmail dropped the connection while it sat idle.
     */
    private <T> T on(Credentials account, Work<T> work) {
        Object lock = locks.computeIfAbsent(account.id(), id -> new Object());
        synchronized (lock) {
            for (int attempt = 1; ; attempt++) {
                try {
                    return work.run(connection(account));
                } catch (AuthenticationFailedException e) {
                    forget(account.id());
                    throw refusedSignIn();
                } catch (FolderClosedException | StoreClosedException e) {
                    forget(account.id());
                    if (attempt >= 2) throw unreachable(e);
                } catch (MessagingException e) {
                    if (attempt >= 2 || !(e.getNextException() instanceof IOException)) throw unreachable(e);
                    forget(account.id());
                } catch (IOException e) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not open this email from Gmail: " + e.getMessage());
                }
            }
        }
    }

    private static ApiException refusedSignIn() {
        return ApiException.badRequest("Gmail would not let us sign in. Check the email and App Password.");
    }

    private static ApiException unreachable(MessagingException e) {
        log.warn("Gmail call failed: {}", e.toString());
        return new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Gmail. Try again soon.");
    }

    private static FolderDef def(String folderId) {
        return FOLDERS.stream().filter(f -> f.id().equals(folderId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Folder not found."));
    }

    private static IMAPFolder folder(Connection connection, String folderId) throws MessagingException {
        String name = connection.folderNames().get(def(folderId).id());
        if (name == null) throw ApiException.notFound("This mailbox has no " + def(folderId).label() + " folder.");
        return (IMAPFolder) connection.store().getFolder(name);
    }

    // ── Folders and lists ───────────────────────────────────────────────────

    /**
     * Unread count and Gmail's next id for the Inbox, without opening it — one
     * small STATUS call, cheap enough to ask every few seconds.
     */
    public MailDtos.Pulse pulse(Credentials account) {
        return on(account, connection -> {
            IMAPFolder inbox = folder(connection, "inbox");
            return new MailDtos.Pulse(Math.max(0, inbox.getUnreadMessageCount()), inbox.getUIDNext());
        });
    }

    public List<MailDtos.Folder> folders(Credentials account) {
        return on(account, connection -> {
            List<MailDtos.Folder> out = new ArrayList<>();
            for (FolderDef def : FOLDERS) {
                if (!connection.folderNames().containsKey(def.id())) continue;
                // Unread counts only where they mean something to act on.
                int unread = def.id().equals("inbox") || def.id().equals("junk")
                        ? Math.max(0, folder(connection, def.id()).getUnreadMessageCount())
                        : 0;
                out.add(new MailDtos.Folder(def.id(), def.label(), def.icon(), unread, def.group()));
            }
            return out;
        });
    }

    /**
     * One page of a folder, newest first. {@code query} is Gmail's own search —
     * the same words that work in Gmail's search box work here.
     */
    public MailDtos.Page list(Credentials account, String folderId, String query, boolean unreadOnly,
                              List<String> addresses, List<String> brands, int page, int size) {
        return on(account, connection -> {
            IMAPFolder folder = folder(connection, folderId);
            folder.open(Folder.READ_ONLY);
            try {
                Message[] matched;
                int total;
                int pageSize = Math.max(1, Math.min(size, 50));
                SearchTerm term = searchTerm(query, unreadOnly, addresses, account.email());

                if (term == null) {
                    total = folder.getMessageCount();
                    int pages = Math.max(1, (total + pageSize - 1) / pageSize);
                    int current = Math.min(Math.max(page, 1), pages);
                    int end = total - (current - 1) * pageSize;
                    int start = Math.max(1, end - pageSize + 1);
                    matched = end < 1 ? new Message[0] : folder.getMessages(start, end);
                    return new MailDtos.Page(summaries(account, folder, folderId, brands, matched), total, current, pageSize, pages);
                }

                Message[] found = folder.search(term);
                total = found.length;
                int pages = Math.max(1, (total + pageSize - 1) / pageSize);
                int current = Math.min(Math.max(page, 1), pages);
                int end = total - (current - 1) * pageSize;
                int start = Math.max(0, end - pageSize);
                matched = Arrays.copyOfRange(found, start, Math.max(start, end));
                return new MailDtos.Page(summaries(account, folder, folderId, brands, matched), total, current, pageSize, pages);
            } finally {
                folder.close(false);
            }
        });
    }

    /**
     * Gmail search words, narrowed to some of our addresses: mail sent to any of
     * them — or, in Sent, from them. Forwarded mail keeps the brand address in
     * its To line. {@code addresses} null means no narrowing; empty means none
     * of our addresses, so nothing matches.
     */
    private static SearchTerm searchTerm(String query, boolean unreadOnly, List<String> addresses, String mailbox) {
        String raw = query == null ? "" : query.strip();
        if (addresses != null) {
            StringBuilder any = new StringBuilder();
            for (String address : addresses) {
                // Everything forwarded is "delivered to" the mailbox itself, so for
                // its own address only the To, Cc and From lines say it was meant for it.
                String delivered = address.equalsIgnoreCase(mailbox) ? "" : " deliveredto:" + address;
                any.append(" to:").append(address).append(" cc:").append(address).append(delivered)
                        .append(" from:").append(address);
                // A brand's tagged mail ("[365loan] …"), which no address names.
                if (!address.equalsIgnoreCase(mailbox)) {
                    String tag = tagOf(address);
                    if (!tag.isEmpty()) any.append(" subject:").append(tag);
                }
            }
            // Gmail reads {a b c} as "any of". With no address at all, ask for one nobody has.
            String narrow = any.isEmpty() ? "to:nobody@invalid.invalid" : "{" + any.toString().strip() + "}";
            raw = (narrow + " " + raw).strip();
        }
        SearchTerm words = raw.isEmpty() ? null : new GmailRawSearchTerm(raw);
        SearchTerm unread = unreadOnly ? new FlagTerm(new Flags(Flags.Flag.SEEN), false) : null;
        if (words != null && unread != null) return new AndTerm(words, unread);
        return words != null ? words : unread;
    }

    private List<MailDtos.Summary> summaries(Credentials account, IMAPFolder folder, String folderId,
                                             List<String> brands, Message[] messages)
            throws MessagingException {
        if (messages.length == 0) return List.of();

        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        profile.add(FetchProfile.Item.CONTENT_INFO);
        profile.add(UIDFolder.FetchProfileItem.UID);
        profile.add(GmailFolder.FetchProfileItem.LABELS);
        folder.fetch(messages, profile);

        // In Sent and Drafts the interesting person is who it went to.
        boolean outgoing = folderId.equals("sent") || folderId.equals("drafts");
        long validity = folder.getUIDValidity();
        List<MailDtos.Summary> out = new ArrayList<>(messages.length);
        // Newest first: IMAP numbers messages oldest first.
        for (int i = messages.length - 1; i >= 0; i--) {
            Message message = messages[i];
            long uid = folder.getUID(message);
            Party from = outgoing ? to(message) : from(message);
            boolean hasAttachments;
            String preview;
            try {
                hasAttachments = hasAttachments(message);
                preview = preview(account, folder.getFullName(), validity, uid, message);
            } catch (IOException | MessagingException e) {
                hasAttachments = false;
                preview = "";
            }
            out.add(new MailDtos.Summary(
                    folderId + "." + uid,
                    from.name(),
                    from.email(),
                    subjectOf(message),
                    receivedAt(message),
                    preview,
                    message.isSet(Flags.Flag.SEEN),
                    message.isSet(Flags.Flag.FLAGGED),
                    hasAttachments,
                    labels(message),
                    brandOf(message, brands)));
        }
        return out;
    }

    private String preview(Credentials account, String folderName, long validity, long uid, Message message)
            throws MessagingException, IOException {
        String key = account.id() + "|" + folderName + "|" + validity + "|" + uid;
        String cached = previews.get(key);
        if (cached != null) return cached;

        Body body = new Body();
        collect(message, body, false);
        String text = body.text != null ? body.text : body.html != null ? htmlToText(body.html) : "";
        String preview = shorten(text.replaceAll("\\s+", " ").strip(), PREVIEW_LENGTH);
        previews.put(key, preview);
        return preview;
    }

    // ── One message ─────────────────────────────────────────────────────────

    private record MailRef(String folderId, long uid) {
    }

    private static MailRef ref(String mailId) {
        int dot = mailId == null ? -1 : mailId.lastIndexOf('.');
        try {
            if (dot > 0) return new MailRef(mailId.substring(0, dot), Long.parseLong(mailId.substring(dot + 1)));
        } catch (NumberFormatException ignored) {
            // Falls through.
        }
        throw ApiException.notFound("Email not found.");
    }

    private static Message find(IMAPFolder folder, long uid) throws MessagingException {
        Message message = folder.getMessageByUID(uid);
        if (message == null) throw ApiException.notFound("This email is not in this folder anymore.");
        return message;
    }

    /** Opens a message and marks it as read in Gmail. */
    public MailDtos.Message open(Credentials account, String mailId, List<String> brands) {
        MailRef ref = ref(mailId);
        return on(account, connection -> {
            IMAPFolder folder = folder(connection, ref.folderId());
            folder.open(Folder.READ_WRITE);
            try {
                Message message = find(folder, ref.uid());
                Body body = new Body();
                collect(message, body, true);
                if (!message.isSet(Flags.Flag.SEEN)) message.setFlag(Flags.Flag.SEEN, true);

                Party from = from(message);
                String text = body.text != null ? body.text : body.html != null ? htmlToText(body.html) : "";
                return new MailDtos.Message(
                        mailId,
                        from.name(),
                        from.email(),
                        addresses(message.getRecipients(Message.RecipientType.TO)),
                        addresses(message.getRecipients(Message.RecipientType.CC)),
                        firstAddress(message.getReplyTo()),
                        subjectOf(message),
                        receivedAt(message),
                        body.html,
                        text,
                        true,
                        message.isSet(Flags.Flag.FLAGGED),
                        labels(message),
                        body.attachments,
                        header(message, "Message-ID"),
                        ref.folderId(),
                        brandOf(message, brands));
            } finally {
                folder.close(false);
            }
        });
    }

    public record AttachmentData(String name, String contentType, byte[] bytes) {
    }

    /** One attached file, by its place in the message. */
    public AttachmentData attachment(Credentials account, String mailId, int index) {
        MailRef ref = ref(mailId);
        return on(account, connection -> {
            IMAPFolder folder = folder(connection, ref.folderId());
            folder.open(Folder.READ_ONLY);
            try {
                Part part = attachmentPart(find(folder, ref.uid()), index);
                try (InputStream in = part.getInputStream()) {
                    return new AttachmentData(fileName(part, index), baseType(part), in.readAllBytes());
                }
            } finally {
                folder.close(false);
            }
        });
    }

    // ── Organising ──────────────────────────────────────────────────────────

    /**
     * Archive, junk, delete, star, read — done in Gmail itself, so the change
     * shows there too. Returns the folder the message is in afterwards, or null
     * when it left the folder it was in.
     */
    public void act(Credentials account, String mailId, String action) {
        MailRef ref = ref(mailId);
        on(account, connection -> {
            IMAPFolder folder = folder(connection, ref.folderId());
            folder.open(Folder.READ_WRITE);
            try {
                Message message = find(folder, ref.uid());
                Message[] one = {message};
                switch (action) {
                    case "read" -> message.setFlag(Flags.Flag.SEEN, true);
                    case "unread" -> message.setFlag(Flags.Flag.SEEN, false);
                    case "star" -> message.setFlag(Flags.Flag.FLAGGED, true);
                    case "unstar" -> message.setFlag(Flags.Flag.FLAGGED, false);
                    case "archive" -> {
                        // In Gmail, archiving is leaving the Inbox; All Mail keeps it.
                        if (!ref.folderId().equals("inbox")) throw ApiException.badRequest("You can only archive emails in the Inbox.");
                        folder.moveMessages(one, folder(connection, "archive"));
                    }
                    case "inbox" -> {
                        switch (ref.folderId()) {
                            case "inbox" -> throw ApiException.badRequest("This email is already in the Inbox.");
                            // Moving out of All Mail would delete it; copying adds the Inbox back.
                            case "archive" -> folder.copyMessages(one, folder(connection, "inbox"));
                            default -> folder.moveMessages(one, folder(connection, "inbox"));
                        }
                    }
                    case "junk" -> {
                        if (ref.folderId().equals("junk")) throw ApiException.badRequest("This email is already in Junk.");
                        folder.moveMessages(one, folder(connection, "junk"));
                    }
                    case "delete" -> {
                        if (ref.folderId().equals("trash")) {
                            // Already in the Trash: this is the permanent delete.
                            message.setFlag(Flags.Flag.DELETED, true);
                            folder.expunge(one);
                        } else {
                            folder.moveMessages(one, folder(connection, "trash"));
                        }
                    }
                    default -> throw ApiException.badRequest("Unknown action \"" + action + "\".");
                }
                return null;
            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        });
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /** {@code fromName}/{@code fromEmail} — the mailbox or one of its brand addresses, already checked. */
    public record Outgoing(String fromName, String fromEmail, String to, String cc, String bcc, String subject,
                           String body, String mode, String originalId, List<MultipartFile> files) {
    }

    /**
     * Sends through Gmail — or, with {@code draft}, saves it to Drafts instead.
     * Returns the brand of the email being answered or forwarded, if any — so
     * a reply can be counted for it whichever address it went out from.
     */
    public String send(Credentials account, Outgoing mail, boolean draft, List<String> brands) {
        String mode = mail.mode() == null ? "new" : mail.mode();
        Original original = mail.originalId() == null || mail.originalId().isBlank() || mode.equals("new")
                ? null
                : on(account, connection -> original(connection, mail.originalId(), mode.equals("forward"), brands));

        try {
            MimeMessage message = new MimeMessage(smtpSession());
            message.setFrom(new InternetAddress(mail.fromEmail(), mail.fromName(), "UTF-8"));
            InternetAddress[] to = parse(mail.to(), "To");
            if (to.length == 0 && !draft) throw ApiException.badRequest("Add at least one person to send to.");
            message.setRecipients(Message.RecipientType.TO, to);
            message.setRecipients(Message.RecipientType.CC, parse(mail.cc(), "Cc"));
            message.setRecipients(Message.RecipientType.BCC, parse(mail.bcc(), "Bcc"));
            message.setSubject(mail.subject() == null ? "" : mail.subject(), "UTF-8");
            message.setSentDate(new Date());

            String text = mail.body() == null ? "" : mail.body();
            if (original != null) {
                text = text + "\n\n" + original.quote();
                if (!mode.equals("forward") && original.messageId() != null) {
                    message.setHeader("In-Reply-To", original.messageId());
                    String refs = original.references() == null ? "" : original.references() + " ";
                    message.setHeader("References", refs + original.messageId());
                }
            }

            List<MimeBodyPart> files = new ArrayList<>();
            for (MultipartFile file : mail.files() == null ? List.<MultipartFile>of() : mail.files()) {
                if (file.isEmpty()) continue;
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new jakarta.activation.DataHandler(
                        new jakarta.mail.util.ByteArrayDataSource(file.getBytes(),
                                file.getContentType() == null ? "application/octet-stream" : file.getContentType())));
                part.setFileName(MimeUtility.encodeText(
                        file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename(), "UTF-8", null));
                part.setDisposition(Part.ATTACHMENT);
                files.add(part);
            }
            if (original != null) files.addAll(original.attachments());

            if (files.isEmpty()) {
                message.setText(text, "UTF-8");
            } else {
                MimeMultipart multipart = new MimeMultipart("mixed");
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(text, "UTF-8");
                multipart.addBodyPart(textPart);
                for (MimeBodyPart part : files) multipart.addBodyPart(part);
                message.setContent(multipart);
            }
            message.saveChanges();

            if (draft) {
                on(account, connection -> {
                    IMAPFolder drafts = folder(connection, "drafts");
                    message.setFlag(Flags.Flag.DRAFT, true);
                    drafts.appendMessages(new Message[]{message});
                    return null;
                });
            } else {
                Transport.send(message, account.email(), account.password());
            }
        } catch (AuthenticationFailedException e) {
            throw refusedSignIn();
        } catch (SendFailedException e) {
            throw ApiException.badRequest("Gmail could not send to some addresses: " + e.getMessage());
        } catch (MessagingException e) {
            throw unreachable(e);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read an attached file: " + e.getMessage());
        }
        return original == null ? null : original.brand();
    }

    private record Original(String messageId, String references, String quote, List<MimeBodyPart> attachments,
                            String brand) {
    }

    /** What a reply or a forward carries from the message it answers. */
    private Original original(Connection connection, String mailId, boolean withAttachments, List<String> brands)
            throws MessagingException, IOException {
        MailRef ref = ref(mailId);
        IMAPFolder folder = folder(connection, ref.folderId());
        folder.open(Folder.READ_ONLY);
        try {
            Message message = find(folder, ref.uid());
            Body body = new Body();
            collect(message, body, true);
            String text = body.text != null ? body.text : body.html != null ? htmlToText(body.html) : "";
            Party from = from(message);
            String who = from.name().isBlank() ? from.email() : from.name() + " <" + from.email() + ">";

            String quote;
            if (withAttachments) {
                quote = "---------- Forwarded message ----------\n"
                        + "From: " + who + "\n"
                        + "Date: " + receivedAt(message) + "\n"
                        + "Subject: " + subjectOf(message) + "\n\n" + text;
            } else {
                quote = "On " + receivedAt(message) + ", " + who + " wrote:\n"
                        + text.lines().map(line -> "> " + line).reduce((a, b) -> a + "\n" + b).orElse("");
            }

            List<MimeBodyPart> attachments = new ArrayList<>();
            if (withAttachments) {
                for (int i = 0; i < body.attachments.size(); i++) {
                    Part part = attachmentPart(message, i);
                    byte[] bytes;
                    try (InputStream in = part.getInputStream()) {
                        bytes = in.readAllBytes();
                    }
                    MimeBodyPart copy = new MimeBodyPart();
                    copy.setDataHandler(new jakarta.activation.DataHandler(
                            new jakarta.mail.util.ByteArrayDataSource(bytes, baseType(part))));
                    copy.setFileName(MimeUtility.encodeText(fileName(part, i), "UTF-8", null));
                    copy.setDisposition(Part.ATTACHMENT);
                    attachments.add(copy);
                }
            }
            return new Original(header(message, "Message-ID"), header(message, "References"), quote, attachments,
                    brandOf(message, brands));
        } finally {
            folder.close(false);
        }
    }

    private static InternetAddress[] parse(String list, String field) {
        if (list == null || list.isBlank()) return new InternetAddress[0];
        try {
            InternetAddress[] addresses = InternetAddress.parse(list.replace(';', ','), false);
            for (InternetAddress address : addresses) address.validate();
            return addresses;
        } catch (AddressException e) {
            throw ApiException.badRequest("The " + field + " box has a wrong address: " + e.getRef());
        }
    }

    // ── Reading the parts of a message ──────────────────────────────────────

    /** The readable body and the list of attached files. */
    private static class Body {
        String text;
        String html;
        final List<MailDtos.Attachment> attachments = new ArrayList<>();
    }

    /**
     * Walks the message: the first plain-text and HTML bodies, and every file.
     * With {@code full} false it stops at the text, which is all a preview needs.
     * Files are numbered in the order they are met, which is how they are asked for.
     */
    private static void collect(Part part, Body body, boolean full) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) collect(multipart.getBodyPart(i), body, full);
            return;
        }
        if (isAttachment(part)) {
            if (full) {
                int index = body.attachments.size();
                body.attachments.add(new MailDtos.Attachment(index, fileName(part, index), baseType(part),
                        fileSize(part)));
            }
            return;
        }
        if (part.isMimeType("text/plain") && body.text == null) {
            if (!full && part.getSize() > PREVIEW_MAX_PART) return;
            body.text = String.valueOf(part.getContent());
        } else if (part.isMimeType("text/html") && body.html == null) {
            if (!full && (body.text != null || part.getSize() > PREVIEW_MAX_PART)) return;
            body.html = String.valueOf(part.getContent());
        }
    }

    /**
     * The file's real size — added 2026-10-26. IMAP gives the size as sent,
     * and most files travel as base64: 4 letters for every 3 bytes, plus a line
     * break every 76 letters, so the listing showed them about 37% too big.
     * Worked out from that rule rather than by downloading the file.
     */
    private static long fileSize(Part part) throws MessagingException {
        long sent = Math.max(0, part.getSize());
        String[] encoding = part.getHeader("Content-Transfer-Encoding");
        boolean base64 = encoding != null && encoding.length > 0 && "base64".equalsIgnoreCase(encoding[0].trim());
        return base64 ? Math.round(sent * 3.0 / 4 * 76 / 78) : sent;
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return true;
        if (part.isMimeType("message/rfc822")) return true;
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) return false;
        return part.getFileName() != null || !part.isMimeType("text/*");
    }

    private static Part attachmentPart(Part root, int wanted) throws MessagingException, IOException {
        int[] seen = {0};
        Part found = findAttachment(root, wanted, seen);
        if (found == null) throw ApiException.notFound("File not found.");
        return found;
    }

    private static Part findAttachment(Part part, int wanted, int[] seen) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                Part found = findAttachment(multipart.getBodyPart(i), wanted, seen);
                if (found != null) return found;
            }
            return null;
        }
        if (isAttachment(part)) {
            if (seen[0] == wanted) return part;
            seen[0]++;
        }
        return null;
    }

    private static boolean hasAttachments(Message message) throws MessagingException, IOException {
        return findAttachment(message, 0, new int[]{0}) != null;
    }

    private static String fileName(Part part, int index) throws MessagingException {
        String name = part.getFileName();
        if (name != null) {
            try {
                return MimeUtility.decodeText(name);
            } catch (UnsupportedEncodingException e) {
                return name;
            }
        }
        if (part.isMimeType("message/rfc822")) return "message-" + (index + 1) + ".eml";
        return "attachment-" + (index + 1);
    }

    private static String baseType(Part part) throws MessagingException {
        try {
            return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
        } catch (ParseException e) {
            return "application/octet-stream";
        }
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private record Party(String name, String email) {
    }

    private static Party from(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) return new Party("(unknown sender)", "");
        if (from[0] instanceof InternetAddress address) {
            String name = address.getPersonal();
            String email = address.getAddress() == null ? "" : address.getAddress();
            return new Party(name == null || name.isBlank() ? email : name, email);
        }
        return new Party(from[0].toString(), from[0].toString());
    }

    private static Party to(Message message) throws MessagingException {
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        if (to == null || to.length == 0) return new Party("(no recipient)", "");
        if (to[0] instanceof InternetAddress address) {
            String name = address.getPersonal();
            String email = address.getAddress() == null ? "" : address.getAddress();
            return new Party("To: " + (name == null || name.isBlank() ? email : name), email);
        }
        return new Party("To: " + to[0], to[0].toString());
    }

    /**
     * Which brand a message belongs to: the first brand address among its
     * recipients — where Cloudflare delivered it from — or its sender, for mail
     * sent from a brand.
     */
    private static String brandOf(Message message, List<String> brands) {
        if (brands == null || brands.isEmpty()) return null;
        try {
            List<Address> seen = new ArrayList<>();
            for (Message.RecipientType type : new Message.RecipientType[]{Message.RecipientType.TO, Message.RecipientType.CC}) {
                Address[] list = message.getRecipients(type);
                if (list != null) seen.addAll(Arrays.asList(list));
            }
            Address[] from = message.getFrom();
            if (from != null) seen.addAll(Arrays.asList(from));
            for (Address address : seen) {
                if (address instanceof InternetAddress ia && ia.getAddress() != null) {
                    String email = ia.getAddress().toLowerCase(Locale.ROOT);
                    if (brands.contains(email)) return email;
                }
            }
            return brandOfTag(message.getSubject(), brands);
        } catch (MessagingException ignored) {
            // No brand, then.
        }
        return null;
    }

    /**
     * "[365loan] New message — Jane" and its replies ("Re: [365loan] …") belong
     * to the brand whose website is 365loan.ca — decided 2026-10-26. A brand's
     * website form can mail the mailbox straight from the mailbox, so no
     * address on it names the brand; the tag at the start of the subject does.
     */
    private static final Pattern SUBJECT_TAG =
            Pattern.compile("^\\s*(?:(?:re|fw|fwd|tr)\\s*:\\s*)*\\[([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    static String brandOfTag(String subject, List<String> brands) {
        if (subject == null || brands == null) return null;
        Matcher tag = SUBJECT_TAG.matcher(subject);
        if (!tag.find()) return null;
        String wanted = plain(tag.group(1));
        if (wanted.isEmpty()) return null;
        for (String brand : brands) {
            if (wanted.equals(tagOf(brand))) return brand;
        }
        return null;
    }

    /** support@365loan.ca → "365loan": the first word of the brand's website. */
    static String tagOf(String brandEmail) {
        int at = brandEmail.indexOf('@');
        String domain = at < 0 ? brandEmail : brandEmail.substring(at + 1);
        int dot = domain.indexOf('.');
        return plain(dot < 0 ? domain : domain.substring(0, dot));
    }

    /** Lower case, letters and digits only — so "365 Loan" and "365loan" match. */
    private static String plain(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static List<String> addresses(Address[] addresses) {
        if (addresses == null) return List.of();
        return Arrays.stream(addresses).map(a -> a instanceof InternetAddress ia ? ia.toUnicodeString() : a.toString()).toList();
    }

    private static String firstAddress(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        return addresses[0] instanceof InternetAddress ia ? ia.getAddress() : addresses[0].toString();
    }

    private static String subjectOf(Message message) throws MessagingException {
        String subject = message.getSubject();
        return subject == null || subject.isBlank() ? "(no subject)" : subject;
    }

    private static Instant receivedAt(Message message) throws MessagingException {
        Date date = message.getReceivedDate();
        if (date == null) date = message.getSentDate();
        return date == null ? Instant.EPOCH : date.toInstant();
    }

    private static String header(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    /** Your own Gmail labels — the system ones (Inbox, Sent, Important…) start with a backslash. */
    private static List<String> labels(Message message) {
        if (!(message instanceof GmailMessage gmail)) return List.of();
        try {
            String[] labels = gmail.getLabels();
            if (labels == null) return List.of();
            return Arrays.stream(labels).filter(l -> !l.startsWith("\\")).sorted().toList();
        } catch (MessagingException e) {
            return List.of();
        }
    }

    /** Readable text from HTML, keeping its line breaks and paragraphs. */
    private static String htmlToText(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        doc.select("style, script, head, title").remove();
        // A marker jsoup's whitespace folding leaves alone, turned into real
        // line breaks afterwards.
        doc.select("br").append("\\n");
        doc.select("p, div, tr, li, h1, h2, h3, h4, h5, h6, blockquote").prepend("\\n");
        String text = doc.body() == null ? "" : doc.body().text();
        return text.replace("\\n", "\n").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
