package org.example.backend.service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.example.backend.service.EmailTemplates.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the emails accounts need: the invitation and the password-reset link.
 *
 * <p>Each one carries both versions from {@link EmailTemplates}: HTML and plain
 * text.
 *
 * <p>With no {@code SPRING_MAIL_HOST} there is nowhere to send mail, so the link
 * is written to the log instead, with a warning — the flow keeps working in local
 * development, and the gap is impossible to miss on a server. Checked here rather
 * than left to Spring, because an empty host is still "a host" to Spring and the
 * failure would surface as a connection error with the link lost.
 *
 * <p><b>Sending happens in the background.</b> Talking to a mail server takes a
 * second or more, and "forgot password" must answer just as fast for an address
 * with no account as for one with an account — otherwise the delay itself tells
 * a stranger who works here.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** A virtual thread per email: nothing to size, and nothing waits on it. */
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${app.mail.from}") String from) {
        this.sender = sender;
        this.host = host;
        this.from = from;
    }

    public void sendInvitation(String to, String firstName, String invitedBy, String link, long validDays) {
        send(to, EmailTemplates.invitation(firstName, invitedBy, link, validDays), link);
    }

    public void sendPasswordReset(String to, String firstName, String link, long validMinutes) {
        send(to, EmailTemplates.passwordReset(firstName, link, validMinutes), link);
    }

    @PreDestroy
    void stop() {
        // Lets an email already on its way finish before the application exits.
        background.close();
    }

    private void send(String to, Email email, String link) {
        background.submit(() -> deliver(to, email, link));
    }

    private void deliver(String to, Email email, String link) {
        JavaMailSender mail = host.isBlank() ? null : sender.getIfAvailable();
        if (mail == null) {
            log.warn("No mail server configured (SPRING_MAIL_HOST) — \"{}\" to {} not sent. Link: {}",
                    email.subject(), to, link);
            return;
        }
        try {
            MimeMessage message = mail.createMimeMessage();
            // true = multipart: the plain-text and HTML versions travel together.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            mail.send(message);
            log.info("Sent \"{}\" to {}", email.subject(), to);
        } catch (MessagingException | MailException e) {
            // Never tell the caller: "forgot password" must answer the same whether or not mail went out.
            log.error("Could not send \"{}\" to {}", email.subject(), to, e);
        }
    }
}
