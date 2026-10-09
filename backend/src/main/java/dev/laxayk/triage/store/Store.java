package dev.laxayk.triage.store;

import dev.laxayk.triage.domain.Rows.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** All SQL lives here. Plain JDBC keeps queries visible and the schema portable between Postgres and H2 tests. */
@Repository
public class Store {
    private final JdbcClient db;

    public Store(JdbcClient db) { this.db = db; }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    private static Long lng(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    // ---------- users ----------
    public Optional<UserRow> findUser(String username) {
        return db.sql("SELECT * FROM users WHERE username = ?").param(username)
                .query((rs, n) -> new UserRow(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("role"))).optional();
    }

    public void insertUser(String username, String hash, String role) {
        db.sql("INSERT INTO users(username, password_hash, role) VALUES (?,?,?)")
                .params(username, hash, role).update();
    }

    public long countUsers() {
        return db.sql("SELECT count(*) FROM users").query(Long.class).single();
    }

    // ---------- emails ----------
    private static EmailRow email(ResultSet rs, int n) throws SQLException {
        return new EmailRow(rs.getLong("id"), rs.getString("message_id"), rs.getString("in_reply_to"),
                rs.getString("refs"), rs.getString("thread_key"), rs.getString("from_addr"),
                rs.getString("from_name"), rs.getString("to_addr"), rs.getString("subject"),
                rs.getString("body"), rs.getString("attachments"), rs.getBoolean("auto_generated"),
                ts(rs, "received_at"), rs.getString("source"), rs.getString("correlation_id"),
                rs.getString("status"), rs.getString("verdict"), rs.getString("verdict_reason"),
                rs.getString("category"), rs.getString("disposition"), lng(rs, "ticket_id"));
    }

    public boolean emailExists(String messageId) {
        return db.sql("SELECT count(*) FROM emails WHERE message_id = ?").param(messageId)
                .query(Long.class).single() > 0;
    }

    /** Idempotent insert: returns empty when the Message-ID was already stored. */
    public Optional<Long> insertEmail(String messageId, String inReplyTo, String refs, String threadKey,
                                      String from, String fromName, String to, String subject, String body,
                                      String attachments, boolean auto, Instant received, String source,
                                      String correlationId) {
        if (emailExists(messageId)) return Optional.empty();
        var kh = new GeneratedKeyHolder();
        db.sql("""
                INSERT INTO emails(message_id,in_reply_to,refs,thread_key,from_addr,from_name,to_addr,subject,body,
                  attachments,auto_generated,received_at,source,correlation_id,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RECEIVED')""")
                .params(messageId, inReplyTo, refs, threadKey, from, fromName, to, subject, body, attachments,
                        auto, ts(received), source, correlationId)
                .update(kh, "id");
        return Optional.of(kh.getKey().longValue());
    }

    public Optional<EmailRow> email(long id) {
        return db.sql("SELECT * FROM emails WHERE id = ?").param(id).query(Store::email).optional();
    }

    public List<EmailRow> emailsByStatus(String status) {
        return db.sql("SELECT * FROM emails WHERE status = ? ORDER BY id").param(status).query(Store::email).list();
    }

    public List<EmailRow> emailsByDisposition(String disposition) {
        return db.sql("SELECT * FROM emails WHERE disposition = ? ORDER BY received_at DESC").param(disposition)
                .query(Store::email).list();
    }

    public List<EmailRow> emailsForTicket(long ticketId) {
        return db.sql("SELECT * FROM emails WHERE ticket_id = ? ORDER BY received_at, id").param(ticketId)
                .query(Store::email).list();
    }

    public List<EmailRow> recentEmails(int limit) {
        return db.sql("SELECT * FROM emails ORDER BY id DESC LIMIT " + limit).query(Store::email).list();
    }

    public Optional<String> threadKeyForMessageId(String messageId) {
        return db.sql("SELECT thread_key FROM emails WHERE message_id = ? AND thread_key IS NOT NULL")
                .param(messageId).query(String.class).optional();
    }

    public void markEmailProcessed(long id, String status, String verdict, String reason, String category,
                                   String disposition, Long ticketId) {
        db.sql("UPDATE emails SET status=?, verdict=?, verdict_reason=?, category=?, disposition=?, ticket_id=? WHERE id=?")
                .params(status, verdict, reason, category, disposition, ticketId, id).update();
    }

    public void setEmailThread(long id, String threadKey) {
        db.sql("UPDATE emails SET thread_key=? WHERE id=?").params(threadKey, id).update();
    }

    // ---------- tickets ----------
    private static TicketRow ticket(ResultSet rs, int n) throws SQLException {
        long id = rs.getLong("id");
        double conf = rs.getDouble("confidence");
        boolean confNull = rs.wasNull();
        return new TicketRow(id, "FS-" + (1000 + id), rs.getString("thread_key"), rs.getString("subject"),
                rs.getString("requester"), rs.getString("category"), rs.getString("priority"),
                rs.getString("status"), rs.getString("assignee"), rs.getString("summary"),
                confNull ? null : conf, rs.getString("classified_by"), rs.getBoolean("needs_review"),
                ts(rs, "created_at"), ts(rs, "updated_at"));
    }

    public Optional<TicketRow> ticketByThread(String threadKey) {
        return db.sql("SELECT * FROM tickets WHERE thread_key = ?").param(threadKey).query(Store::ticket).optional();
    }

    public Optional<TicketRow> openTicketBySubject(String requester, String normalizedSubject) {
        return db.sql("SELECT * FROM tickets WHERE requester = ? AND lower(subject) = ? AND status <> 'CLOSED' ORDER BY id DESC LIMIT 1")
                .params(requester, normalizedSubject).query(Store::ticket).optional();
    }

    public Optional<TicketRow> ticket(long id) {
        return db.sql("SELECT * FROM tickets WHERE id = ?").param(id).query(Store::ticket).optional();
    }

    public long insertTicket(String threadKey, String subject, String requester, String category, String priority,
                             String assignee, String summary, Double confidence, String classifiedBy,
                             boolean needsReview) {
        var kh = new GeneratedKeyHolder();
        Instant now = Instant.now();
        db.sql("""
                INSERT INTO tickets(thread_key,subject,requester,category,priority,status,assignee,summary,confidence,
                  classified_by,needs_review,created_at,updated_at)
                VALUES (?,?,?,?,?,'OPEN',?,?,?,?,?,?,?)""")
                .params(threadKey, subject, requester, category, priority, assignee, summary, confidence,
                        classifiedBy, needsReview, ts(now), ts(now))
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public void touchTicket(long id) {
        db.sql("UPDATE tickets SET updated_at=? WHERE id=?").params(ts(Instant.now()), id).update();
    }

    public void reopenTicket(long id) {
        db.sql("UPDATE tickets SET status='OPEN', updated_at=? WHERE id=? AND status IN ('RESOLVED')")
                .params(ts(Instant.now()), id).update();
    }

    public void updateTicket(long id, String category, String priority, String status, String assignee) {
        db.sql("UPDATE tickets SET category=?, priority=?, status=?, assignee=?, needs_review=FALSE, updated_at=? WHERE id=?")
                .params(category, priority, status, assignee, ts(Instant.now()), id).update();
    }

    public List<TicketRow> listTickets(String status, String category, String q) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tickets WHERE 1=1");
        List<Object> p = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); p.add(status); }
        if (category != null && !category.isBlank()) { sql.append(" AND category = ?"); p.add(category); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (lower(subject) LIKE ? OR lower(requester) LIKE ? OR lower(summary) LIKE ?)");
            String like = "%" + q.toLowerCase() + "%";
            p.add(like); p.add(like); p.add(like);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT 300");
        return db.sql(sql.toString()).params(p).query(Store::ticket).list();
    }

    // ---------- drafts / outbox ----------
    private static DraftRow draft(ResultSet rs, int n) throws SQLException {
        return new DraftRow(rs.getLong("id"), rs.getLong("ticket_id"), rs.getString("body"),
                rs.getString("evidence"), rs.getString("generated_by"), rs.getString("status"),
                rs.getString("approved_by"), rs.getString("body_hash"), ts(rs, "created_at"), ts(rs, "decided_at"));
    }

    public long insertDraft(long ticketId, String body, String evidence, String generatedBy) {
        var kh = new GeneratedKeyHolder();
        db.sql("INSERT INTO drafts(ticket_id,body,evidence,generated_by,status,created_at) VALUES (?,?,?,?,'DRAFT',?)")
                .params(ticketId, body, evidence, generatedBy, ts(Instant.now())).update(kh, "id");
        return kh.getKey().longValue();
    }

    public Optional<DraftRow> draft(long id) {
        return db.sql("SELECT * FROM drafts WHERE id=?").param(id).query(Store::draft).optional();
    }

    public List<DraftRow> draftsForTicket(long ticketId) {
        return db.sql("SELECT * FROM drafts WHERE ticket_id=? ORDER BY id DESC").param(ticketId)
                .query(Store::draft).list();
    }

    public void updateDraftBody(long id, String body) {
        db.sql("UPDATE drafts SET body=? WHERE id=? AND status='DRAFT'").params(body, id).update();
    }

    /** Only a DRAFT can be decided, so a double click cannot send twice. */
    public boolean decideDraft(long id, String status, String actor, String hash) {
        return db.sql("UPDATE drafts SET status=?, approved_by=?, body_hash=?, decided_at=? WHERE id=? AND status='DRAFT'")
                .params(status, actor, hash, ts(Instant.now()), id).update() == 1;
    }

    public void insertOutbox(long draftId, String recipient, String subject, String body, String mode) {
        db.sql("INSERT INTO outbox(draft_id,recipient,subject,body,mode,sent_at,kind) VALUES (?,?,?,?,?,?,'REPLY')")
                .params(draftId, recipient, subject, body, mode, ts(Instant.now())).update();
    }

    public void insertOwnerAlert(long ticketId, String recipient, String subject, String body, String mode) {
        db.sql("INSERT INTO outbox(draft_id,ticket_id,recipient,subject,body,mode,sent_at,kind) VALUES (NULL,?,?,?,?,?,?,'OWNER_ALERT')")
                .params(ticketId, recipient, subject, body, mode, ts(Instant.now())).update();
    }

    public boolean ownerAlertExists(long ticketId) {
        return db.sql("SELECT count(*) FROM outbox WHERE ticket_id=? AND kind='OWNER_ALERT'").param(ticketId)
                .query(Long.class).single() > 0;
    }

    public record OwnerMail(long id, String recipient, String subject, String body, String mode, Instant sentAt) {}

    public List<OwnerMail> ownerMails(long ticketId) {
        return db.sql("SELECT * FROM outbox WHERE ticket_id=? AND kind='OWNER_ALERT' ORDER BY id").param(ticketId)
                .query((rs, n) -> new OwnerMail(rs.getLong("id"), rs.getString("recipient"), rs.getString("subject"),
                        rs.getString("body"), rs.getString("mode"), ts(rs, "sent_at"))).list();
    }

    public record TicketBrief(String key, String subject, String category, String status) {}

    public List<TicketBrief> ticketHistory(String requester, long excludeTicketId) {
        return db.sql("SELECT * FROM tickets WHERE requester=? AND id<>? ORDER BY id DESC LIMIT 5").params(requester, excludeTicketId)
                .query((rs, n) -> new TicketBrief("FS-" + (1000 + rs.getLong("id")), rs.getString("subject"),
                        rs.getString("category"), rs.getString("status"))).list();
    }

    public List<TicketBrief> openIncidents() {
        return db.sql("SELECT * FROM tickets WHERE category='OUTAGE' AND status IN ('OPEN','IN_PROGRESS') ORDER BY id DESC LIMIT 10")
                .query((rs, n) -> new TicketBrief("FS-" + (1000 + rs.getLong("id")), rs.getString("subject"),
                        rs.getString("category"), rs.getString("status"))).list();
    }

    // ---------- draft steps and knowledge chunks ----------
    public record StepRow(long id, int stepNo, String kind, String tool, String args, String result, long latencyMs) {}

    public void insertDraftStep(long draftId, int stepNo, String kind, String tool, String args, String result, long latencyMs) {
        db.sql("INSERT INTO draft_steps(draft_id,step_no,kind,tool,args,result,latency_ms) VALUES (?,?,?,?,?,?,?)")
                .params(draftId, stepNo, kind, tool, args, result, latencyMs).update();
    }

    public List<StepRow> draftSteps(long draftId) {
        return db.sql("SELECT * FROM draft_steps WHERE draft_id=? ORDER BY step_no").param(draftId)
                .query((rs, n) -> new StepRow(rs.getLong("id"), rs.getInt("step_no"), rs.getString("kind"), rs.getString("tool"),
                        rs.getString("args"), rs.getString("result"), rs.getLong("latency_ms"))).list();
    }

    public record KbRow(String sha, String embedder, String vector) {}

    public Optional<KbRow> kbChunk(String source) {
        return db.sql("SELECT content_sha, embedder, vector FROM kb_chunks WHERE source=?").param(source)
                .query((rs, n) -> new KbRow(rs.getString(1), rs.getString(2), rs.getString(3))).optional();
    }

    public void upsertKbChunk(String source, String type, String title, String sha, String embedder, String vector) {
        int n = db.sql("UPDATE kb_chunks SET doc_type=?, title=?, content_sha=?, embedder=?, vector=? WHERE source=?")
                .params(type, title, sha, embedder, vector, source).update();
        if (n == 0) db.sql("INSERT INTO kb_chunks(source,doc_type,title,content_sha,embedder,vector) VALUES (?,?,?,?,?,?)")
                .params(source, type, title, sha, embedder, vector).update();
    }

    public long countOutbox() {
        return db.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }

    // ---------- notifications / audit / events ----------
    public void insertNotification(Long ticketId, Long emailId, String channel, String target, String text,
                                   boolean delivered) {
        db.sql("INSERT INTO notifications(ticket_id,email_id,channel,target,text,delivered,created_at) VALUES (?,?,?,?,?,?,?)")
                .params(ticketId, emailId, channel, target, text, delivered, ts(Instant.now())).update();
    }

    public List<NotificationRow> notificationsForTicket(long ticketId) {
        return db.sql("SELECT * FROM notifications WHERE ticket_id=? ORDER BY id").param(ticketId)
                .query((rs, n) -> new NotificationRow(rs.getLong("id"), lng(rs, "ticket_id"), lng(rs, "email_id"),
                        rs.getString("channel"), rs.getString("target"), rs.getString("text"),
                        rs.getBoolean("delivered"), ts(rs, "created_at"))).list();
    }

    public void audit(String actor, String action, Long ticketId, Long emailId, String detail) {
        db.sql("INSERT INTO audit_log(actor,action,ticket_id,email_id,detail,at) VALUES (?,?,?,?,?,?)")
                .params(actor, action, ticketId, emailId, detail, ts(Instant.now())).update();
    }

    public List<AuditRow> auditForTicket(long ticketId) {
        return db.sql("SELECT * FROM audit_log WHERE ticket_id=? ORDER BY id").param(ticketId)
                .query((rs, n) -> new AuditRow(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                        lng(rs, "ticket_id"), lng(rs, "email_id"), rs.getString("detail"), ts(rs, "at"))).list();
    }

    public void event(String correlationId, Long emailId, String stage, String detail, long latencyMs) {
        db.sql("INSERT INTO pipeline_events(correlation_id,email_id,stage,detail,latency_ms,at) VALUES (?,?,?,?,?,?)")
                .params(correlationId, emailId, stage, detail, latencyMs, ts(Instant.now())).update();
    }

    public List<EventRow> eventsForEmail(long emailId) {
        return db.sql("SELECT * FROM pipeline_events WHERE email_id=? ORDER BY id").param(emailId)
                .query((rs, n) -> new EventRow(rs.getLong("id"), rs.getString("correlation_id"), lng(rs, "email_id"),
                        rs.getString("stage"), rs.getString("detail"), rs.getLong("latency_ms"), ts(rs, "at"))).list();
    }

    // ---------- stats ----------
    public Map<String, Long> countBy(String table, String column) {
        Map<String, Long> out = new LinkedHashMap<>();
        db.sql("SELECT " + column + " AS k, count(*) AS c FROM " + table + " WHERE " + column + " IS NOT NULL GROUP BY " + column + " ORDER BY c DESC")
                .query((rs, n) -> { out.put(rs.getString("k"), rs.getLong("c")); return null; }).list();
        return out;
    }

    public Map<String, Double> avgLatencyByStage() {
        Map<String, Double> out = new LinkedHashMap<>();
        db.sql("SELECT stage, avg(latency_ms) AS a FROM pipeline_events GROUP BY stage ORDER BY stage")
                .query((rs, n) -> { out.put(rs.getString("stage"), rs.getDouble("a")); return null; }).list();
        return out;
    }

    public List<Long> latenciesForStage(String stage) {
        return db.sql("SELECT latency_ms FROM pipeline_events WHERE stage=? ORDER BY latency_ms").param(stage)
                .query(Long.class).list();
    }
}
