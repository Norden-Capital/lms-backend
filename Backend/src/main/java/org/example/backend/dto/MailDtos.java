package org.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * What the inbox endpoints send and receive. Kept together: they are one
 * feature, and a dozen one-line files would scatter it.
 */
public final class MailDtos {

    private MailDtos() {
    }

    /** A connected mailbox. The app password is never part of it. */
    public record Account(String id, String label, String email, String brand, String provider) {
    }

    public record ConnectAccount(
            @NotBlank @Size(max = 80) String label,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 80) String brand,
            @NotBlank @Size(max = 64) String appPassword) {
    }

    /**
     * @param group "working" for the everyday folders, "aside" for junk, trash and the archive
     */
    public record Folder(String id, String label, String icon, int unread, String group) {
    }

    /**
     * One message in a list.
     *
     * @param id      "inbox.1234" — the folder and Gmail's id for it in that folder
     * @param preview the first few lines, as plain text
     * @param labels  the Gmail labels you made yourself (not Inbox, Sent…)
     */
    public record Summary(String id, String name, String email, String subject, Instant receivedAt,
                          String preview, boolean read, boolean starred, boolean hasAttachments,
                          List<String> labels) {
    }

    /** {@code page} is 1-based; the newest messages are on page 1. */
    public record Page(List<Summary> mails, int total, int page, int size, int totalPages) {
    }

    /** A file attached to a message. {@code index} is how to ask for it. */
    public record Attachment(int index, String name, String contentType, long size) {
    }

    /**
     * One message, open.
     *
     * @param html      the message as the sender wrote it, or null when it is plain text only.
     *                  Shown in a sandboxed frame — never inserted into the page.
     * @param text      the plain-text version; made from the HTML when there is none
     * @param messageId the Message-ID header, which replies point back at
     */
    public record Message(String id, String name, String email, List<String> to, List<String> cc,
                          String replyTo, String subject, Instant receivedAt, String html, String text,
                          boolean read, boolean starred, List<String> labels,
                          List<Attachment> attachments, String messageId, String folder) {
    }

    /** archive, unarchive, junk, notjunk, delete, star, unstar, read, unread. */
    public record Action(@NotBlank String action) {
    }

    public record Link(String url) {
    }
}
