package org.example.backend.service;

import org.springframework.web.util.HtmlUtils;

/**
 * What the platform's emails say and look like.
 *
 * <p>Every email goes out as two versions in one message: HTML, and plain text
 * for mail apps that do not show HTML (it also helps with spam filters). The HTML
 * uses tables and inline styles only — the one thing Gmail, Outlook and phone
 * apps all agree on.
 *
 * <p>No images. An email has no site of its own to load them from, many clients
 * block remote images by default, and a broken logo looks worse than a word.
 *
 * <p>⚠️ Everything a person typed — a name — is escaped before it goes into the
 * HTML, so nobody can put markup in someone's inbox.
 */
public final class EmailTemplates {

    private EmailTemplates() {
    }

    /** One email: its subject, and the same content as plain text and as HTML. */
    public record Email(String subject, String text, String html) {
    }

    private static final String BRAND = "Norden Capital";
    private static final String TEXT = "font-size:14px;line-height:1.5;";
    private static final String NOTE = "margin-top:20px;font-size:12px;line-height:1.4;color:#71717a;";

    public static Email invitation(String firstName, String invitedBy, String link, long validDays) {
        String text = """
                Hello %s,

                %s invited you to join the %s platform.

                Open this link to choose your password (it works once, for %d days):

                %s

                After that you will set up two-factor authentication with an app such as
                Google Authenticator.

                If you weren't expecting this, you can ignore this email.
                """.formatted(firstName, invitedBy, BRAND, validDays, link);

        String card = """
                <div style="text-align:left;%s">%s%s%s</div>
                %s
                <div style="%s">If you weren&#39;t expecting this, you can ignore this email.</div>"""
                .formatted(TEXT,
                        paragraph("Hello " + escape(firstName) + ","),
                        paragraph("<strong>" + escape(invitedBy) + "</strong> invited you to join the "
                                + BRAND + " platform."),
                        paragraph("Click the button to choose your password. The link works once, for <strong>"
                                + validDays + "&nbsp;days</strong>. After that you will set up two-factor "
                                + "authentication with an app such as Google Authenticator."),
                        button(link, "Set up my account"),
                        NOTE);

        String html = page(card, "You are receiving this email because someone invited you to " + BRAND + ".");
        return new Email("You're invited to " + BRAND, text, html);
    }

    public static Email passwordReset(String firstName, String link, long validMinutes) {
        String text = """
                Hello %s,

                Someone asked to reset the password of your %s account.
                Open this link to choose a new one (it works once, for %d minutes):

                %s

                Your two-factor authentication stays the same.

                If it wasn't you, ignore this email — your password stays the same.
                """.formatted(firstName, BRAND, validMinutes, link);

        String card = """
                <div style="text-align:left;%s">%s%s</div>
                %s
                <div style="%s">If it wasn&#39;t you, ignore this email &mdash; your password stays the same.</div>"""
                .formatted(TEXT,
                        paragraph("Hello " + escape(firstName) + ","),
                        paragraph("Someone asked to reset the password of your " + BRAND + " account. "
                                + "Click the button to choose a new one. The link works once, for <strong>"
                                + validMinutes + "&nbsp;minutes</strong>."),
                        button(link, "Choose a new password"),
                        NOTE);

        String html = page(card, "You are receiving this email because a password reset was requested for your account.");
        return new Email("Reset your " + BRAND + " password", text, html);
    }

    // ── The shared frame ────────────────────────────────────────────────────

    /**
     * Grey background, the mark, the white card, the footer under it — centred
     * top to bottom where the mail app allows it (Apple Mail, the preview page).
     * Gmail and Outlook ignore the height and show it at the top, as usual.
     */
    private static String page(String card, String whyLine) {
        return """
                <!doctype html>
                <html lang="en" style="height:100%%;">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="color-scheme" content="light">
                </head>
                <body style="margin:0;padding:0;height:100%%;background:#f4f4f5;color:#202124;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" width="100%%" height="100%%" cellpadding="0" cellspacing="0" border="0" style="height:100%%;background:#f4f4f5;">
                    <tr><td align="center" valign="middle" style="padding:24px 16px;">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:520px;">
                        <tr><td align="center" style="padding:0 0 20px 0;">
                          %s
                        </td></tr>
                        <tr><td style="background:#ffffff;border:1px solid #e4e4e7;border-radius:8px;padding:32px 28px;text-align:center;">
                          %s
                        </td></tr>
                        <tr><td style="padding:16px 8px 0 8px;text-align:center;font-size:11px;line-height:1.6;color:#71717a;">
                          %s<br>
                          This is an automatic message &mdash; replies to it are not read.<br>
                          &copy; %s
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(MARK, card, whyLine, BRAND);
    }

    /**
     * The app's mark — the gallery icon in a small white card, as on the sign-in
     * screen — built from styled boxes rather than an image.
     *
     * <p>Not a picture on purpose: most mail apps hide remote images until the
     * reader allows them, and an inline SVG is dropped by Gmail outright. Boxes
     * with borders show everywhere. Drawn on a 20px grid from lucide's
     * gallery-vertical-end: two bars, then a frame.
     */
    private static final String MARK = """
            <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 auto;"><tr><td align="center" valign="middle" width="38" height="38" style="width:38px;height:38px;background:#ffffff;border:1px solid #e4e4e7;border-radius:8px;box-shadow:0 1px 2px rgba(0,0,0,0.05);">\
            <div style="width:20px;height:20px;margin:0 auto;font-size:0;line-height:0;">\
            <div style="width:8px;height:2px;margin:1px auto 0 auto;background:#09090b;border-radius:1px;font-size:0;line-height:0;"></div>\
            <div style="width:12px;height:2px;margin:2px auto 0 auto;background:#09090b;border-radius:1px;font-size:0;line-height:0;"></div>\
            <div style="width:12px;height:8px;margin:1px auto 0 auto;border:2px solid #09090b;border-radius:2px;font-size:0;line-height:0;"></div>\
            </div></td></tr></table>""";

    private static String button(String href, String label) {
        return """
                <table role="presentation" align="center" cellpadding="0" cellspacing="0" border="0" style="margin:8px auto 0 auto;"><tr><td style="background:#18181b;border-radius:8px;"><a href="%s" style="display:inline-block;padding:10px 18px;font-size:14px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">%s</a></td></tr></table>"""
                .formatted(escape(href), escape(label));
    }

    /** One block of the left-aligned intro, with a blank line's worth of space under it. */
    private static String paragraph(String html) {
        return "<p style=\"margin:0 0 18px 0;" + TEXT + "\">" + html + "</p>";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
