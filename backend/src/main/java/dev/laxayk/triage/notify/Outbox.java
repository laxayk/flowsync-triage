package dev.laxayk.triage.notify;

import dev.laxayk.triage.store.Store;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends approved replies. Default mode is "capture": the reply is stored in the outbox table and shown in the dashboard,
 * nothing leaves the machine. Mode "live" sends through SMTP using the dedicated test mailbox.
 */
@Service
public class Outbox {
    private final Store store;
    private final ObjectProvider<JavaMailSender> mail;
    private final String mode, from;

    public Outbox(Store store, ObjectProvider<JavaMailSender> mail, @Value("${app.send.mode:capture}") String mode,
                  @Value("${app.send.from:}") String from) {
        this.store = store;
        this.mail = mail;
        this.mode = mode;
        this.from = from;
    }

    public String mode() { return mode; }

    /** Internal brief to a department owner (not a customer reply). Same capture/live switch as replies. */
    public String sendInternal(long ticketId, String to, String subject, String body) {
        if (mode.equals("live")) {
            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null || from.isBlank()) throw new IllegalStateException("live send requires SMTP settings and SEND_FROM");
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
        }
        store.insertOwnerAlert(ticketId, to, subject, body, mode);
        return mode;
    }

    /** Returns the effective mode used ("capture" or "live"). Throws if live sending is misconfigured. */
    public String send(long draftId, String to, String subject, String body) {
        if (mode.equals("live")) {
            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null || from.isBlank()) throw new IllegalStateException("live send requires SMTP settings and SEND_FROM");
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject.toLowerCase().startsWith("re:") ? subject : "Re: " + subject);
            m.setText(body);
            sender.send(m);
        }
        store.insertOutbox(draftId, to, subject, body, mode);
        return mode;
    }
}
