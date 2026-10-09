package dev.laxayk.triage.ingest;

import dev.laxayk.triage.domain.ParsedEmail;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Turns a raw RFC 822 message into a ParsedEmail. Attachments are listed, never opened or executed. */
public final class EmailParser {
    private EmailParser() {}

    public static ParsedEmail parse(InputStream raw) throws MessagingException, IOException {
        return parse(new MimeMessage(Session.getInstance(new Properties()), raw));
    }

    public static ParsedEmail parse(MimeMessage m) throws MessagingException, IOException {
        String messageId = m.getMessageID();
        if (messageId == null || messageId.isBlank()) messageId = "<generated-" + UUID.randomUUID() + "@triage.local>";
        String from = "", fromName = "";
        Address[] fa = m.getFrom();
        if (fa != null && fa.length > 0 && fa[0] instanceof InternetAddress ia) {
            from = ia.getAddress() == null ? "" : ia.getAddress().toLowerCase();
            fromName = ia.getPersonal() == null ? "" : ia.getPersonal();
        }
        Address[] ta = m.getRecipients(Message.RecipientType.TO);
        String to = ta == null || ta.length == 0 ? "" : ((InternetAddress) ta[0]).getAddress();
        String[] refs = m.getHeader("References");
        String subject = m.getSubject() == null ? "(no subject)" : MimeUtility.decodeText(m.getSubject());
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<ParsedEmail.Attachment> atts = new ArrayList<>();
        walk(m, text, html, atts);
        String body = text.length() > 0 ? text.toString() : stripHtml(html.toString());
        java.util.Date d = m.getSentDate();
        return new ParsedEmail(messageId.trim(), clean(m.getHeader("In-Reply-To")), refs == null ? null : String.join(" ", refs),
                from, fromName, to, subject.trim(), body.trim(), d == null ? Instant.now() : d.toInstant(),
                isAuto(m), atts);
    }

    private static String clean(String[] h) {
        return h == null || h.length == 0 ? null : h[0].trim();
    }

    private static boolean isAuto(MimeMessage m) throws MessagingException {
        String auto = clean(m.getHeader("Auto-Submitted"));
        if (auto != null && !auto.equalsIgnoreCase("no")) return true;
        String prec = clean(m.getHeader("Precedence"));
        if (prec != null && (prec.equalsIgnoreCase("bulk") || prec.equalsIgnoreCase("auto_reply") || prec.equalsIgnoreCase("junk"))) return true;
        return m.getHeader("X-Autoreply") != null || m.getHeader("X-Autorespond") != null
                || "OOF".equalsIgnoreCase(clean(m.getHeader("X-Auto-Response-Suppress")));
    }

    private static void walk(Part p, StringBuilder text, StringBuilder html, List<ParsedEmail.Attachment> atts)
            throws MessagingException, IOException {
        String disp = p.getDisposition();
        String fileName = p.getFileName();
        if (fileName != null || Part.ATTACHMENT.equalsIgnoreCase(disp)) {
            atts.add(new ParsedEmail.Attachment(fileName == null ? "unnamed" : MimeUtility.decodeText(fileName),
                    p.getContentType() == null ? "" : p.getContentType().split(";")[0].trim(), Math.max(0, p.getSize())));
            return;
        }
        if (p.isMimeType("text/plain")) {
            text.append(p.getContent()).append('\n');
        } else if (p.isMimeType("text/html")) {
            html.append(p.getContent()).append('\n');
        } else if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), text, html, atts);
        }
    }

    static String stripHtml(String h) {
        return h.replaceAll("(?is)<(script|style).*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("[ \\t]+", " ").trim();
    }
}
