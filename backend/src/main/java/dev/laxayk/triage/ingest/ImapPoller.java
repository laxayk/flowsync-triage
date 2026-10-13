package dev.laxayk.triage.ingest;

import dev.laxayk.triage.pipeline.Pipeline;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Polls one dedicated support mailbox over IMAP (a test Gmail with an app password). Disabled unless IMAP_ENABLED=true.
 * Unread mail is parsed, handed to the pipeline and marked read. Duplicates are harmless because intake dedupes on Message-ID.
 */
@Component
public class ImapPoller {
    private static final Logger log = LoggerFactory.getLogger(ImapPoller.class);
    private final Pipeline pipeline;
    private final boolean enabled;
    private final String host, user, password, folder;
    private final int port, maxPerPoll;

    private final int maxAgeDays;
    /** UIDs of messages that failed to parse. They stay unread in the mailbox but are skipped, so one bad mail cannot block the backlog behind it. */
    private final java.util.Set<Long> poison = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ImapPoller(Pipeline pipeline, @Value("${app.imap.enabled:false}") boolean enabled,
                      @Value("${app.imap.host:imap.gmail.com}") String host, @Value("${app.imap.port:993}") int port,
                      @Value("${app.imap.user:}") String user, @Value("${app.imap.password:}") String password,
                      @Value("${app.imap.folder:INBOX}") String folder, @Value("${app.imap.max-per-poll:25}") int maxPerPoll,
                      @Value("${app.imap.max-age-days:0}") int maxAgeDays) {
        this.maxAgeDays = maxAgeDays;
        this.pipeline = pipeline;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.folder = folder;
        this.maxPerPoll = maxPerPoll;
    }

    @Scheduled(fixedDelayString = "${app.imap.poll-seconds:30}000", initialDelay = 10_000)
    public void poll() {
        if (!enabled) return;
        if (user.isBlank() || password.isBlank()) {
            log.warn("IMAP enabled but IMAP_USER / IMAP_PASSWORD are empty");
            return;
        }
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "20000");
        try (Store store = Session.getInstance(props).getStore("imaps")) {
            store.connect(host, port, user, password);
            try (Folder f = store.getFolder(folder)) {
                f.open(Folder.READ_WRITE);
                jakarta.mail.search.SearchTerm term = new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false);
                if (maxAgeDays > 0)
                    term = new jakarta.mail.search.AndTerm(term, new jakarta.mail.search.ReceivedDateTerm(jakarta.mail.search.ComparisonTerm.GE,
                            java.util.Date.from(java.time.Instant.now().minus(java.time.Duration.ofDays(maxAgeDays)))));
                Message[] unread = f.search(term);
                int count = 0, skipped = 0;
                for (Message m : unread) {
                    long uid = f instanceof jakarta.mail.UIDFolder u ? u.getUID(m) : -1;
                    if (uid >= 0 && poison.contains(uid)) { skipped++; continue; }
                    if (count++ >= maxPerPoll) break;
                    try {
                        pipeline.ingest(EmailParser.parse(new MimeMessage((MimeMessage) m)), "imap");
                        m.setFlag(Flags.Flag.SEEN, true);
                    } catch (Exception e) {
                        if (uid >= 0) poison.add(uid);
                        log.warn("Skipping one message that could not be parsed ({}); it stays unread and is not retried until restart", e.getClass().getSimpleName());
                    }
                }
                if (skipped > 0) log.info("IMAP poll skipped {} previously failed message(s)", skipped);
                if (count > 0) log.info("IMAP poll handled {} message(s)", Math.min(count, maxPerPoll));
            }
        } catch (Exception e) {
            log.warn("IMAP poll failed: {}", e.getClass().getSimpleName());
        }
    }
}
