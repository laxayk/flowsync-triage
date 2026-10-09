package dev.laxayk.triage.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.laxayk.triage.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Records every notification in the database (so the dashboard shows who was told what) and, when a Slack webhook
 * is configured, posts it there. Email notifications are captured only; WhatsApp is a stretch goal.
 * All text is sanitised first: email content is untrusted and must not inject mentions, links or markup.
 */
@Service
public class Notifier {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private final Store store;
    private final ObjectMapper mapper;
    private final String slackWebhook;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public Notifier(Store store, ObjectMapper mapper, @Value("${app.notify.slack-webhook:}") String slackWebhook) {
        this.store = store;
        this.mapper = mapper;
        this.slackWebhook = slackWebhook;
    }

    public static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n]+", " ")
                .replace("<!channel>", "").replace("<!here>", "").replace("<!everyone>", "")
                .replaceAll("<[^>]*>", "")
                .replace("@channel", "channel").replace("@here", "here").replace("@everyone", "everyone")
                .replaceAll("https?://\\S+", "[link removed]")
                .trim();
        return t.length() > 220 ? t.substring(0, 220) + "..." : t;
    }

    public void notifyAssignee(Long ticketId, Long emailId, String assignee, String text) {
        send(ticketId, emailId, "slack", "#support-triage", text);
        if (assignee != null) send(ticketId, emailId, "email", assignee + "@flowsync.example", text);
    }

    public void notifySecurity(Long emailId, String text) {
        send(null, emailId, "slack", "#security-review", text);
    }

    private void send(Long ticketId, Long emailId, String channel, String target, String text) {
        boolean delivered = false;
        if (channel.equals("slack") && !slackWebhook.isBlank()) {
            try {
                String payload = mapper.writeValueAsString(Map.of("text", text));
                var req = HttpRequest.newBuilder(URI.create(slackWebhook)).timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                delivered = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
            } catch (Exception e) {
                log.warn("Slack notification failed: {}", e.getClass().getSimpleName());
            }
        }
        store.insertNotification(ticketId, emailId, channel, target, text, delivered);
    }
}
