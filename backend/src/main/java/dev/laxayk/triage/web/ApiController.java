package dev.laxayk.triage.web;

import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.Rows.*;
import dev.laxayk.triage.gate.SafetyGate;
import dev.laxayk.triage.ingest.SampleLoader;
import dev.laxayk.triage.llm.LlmClient;
import dev.laxayk.triage.notify.Outbox;
import dev.laxayk.triage.pipeline.Evaluator;
import dev.laxayk.triage.pipeline.Pipeline;
import dev.laxayk.triage.store.Store;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");

    private final Store store;
    private final Auth auth;
    private final Pipeline pipeline;
    private final SampleLoader samples;
    private final EventHub hub;
    private final Outbox outbox;
    private final LlmClient llm;
    private final Evaluator evaluator;
    private final dev.laxayk.triage.agent.KnowledgeBase kb;

    public ApiController(Store store, Auth auth, Pipeline pipeline, SampleLoader samples, EventHub hub, Outbox outbox, LlmClient llm, Evaluator evaluator, dev.laxayk.triage.agent.KnowledgeBase kb) {
        this.evaluator = evaluator;
        this.kb = kb;
        this.store = store;
        this.auth = auth;
        this.pipeline = pipeline;
        this.samples = samples;
        this.hub = hub;
        this.outbox = outbox;
        this.llm = llm;
    }

    private static Auth.Session who(HttpServletRequest r) { return (Auth.Session) r.getAttribute("session"); }

    private static void requireApprover(HttpServletRequest r) {
        if (!"APPROVER".equals(who(r).role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "approver role required");
    }

    // ---------- auth ----------
    public record LoginReq(String username, String password) {}

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        String token = auth.login(req.username(), req.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad credentials"));
        Auth.Session s = auth.session(token);
        return Map.of("token", token, "username", s.username(), "role", s.role());
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest r) {
        auth.logout(Auth.tokenOf(r));
        return Map.of("ok", true);
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "UP"); }

    @GetMapping("/me")
    public Auth.Session me(HttpServletRequest r) { return who(r); }

    // ---------- tickets ----------
    @GetMapping("/tickets")
    public List<TicketRow> tickets(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String q) {
        return store.listTickets(status, category, q);
    }

    @GetMapping("/tickets/{id}")
    public Map<String, Object> ticket(@PathVariable long id) {
        TicketRow t = store.ticket(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<EmailRow> emails = store.emailsForTicket(id);
        List<EventRow> events = new ArrayList<>();
        for (EmailRow e : emails) events.addAll(store.eventsForEmail(e.id()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", t);
        out.put("emails", emails);
        var drafts = store.draftsForTicket(id);
        out.put("drafts", drafts);
        Map<Long, Object> steps = new LinkedHashMap<>();
        for (DraftRow dr : drafts) steps.put(dr.id(), store.draftSteps(dr.id()));
        out.put("draftSteps", steps);
        out.put("ownerMails", store.ownerMails(id));
        out.put("notifications", store.notificationsForTicket(id));
        out.put("events", events);
        out.put("audit", store.auditForTicket(id));
        return out;
    }

    public record TicketPatch(String category, String priority, String status, String assignee) {}

    @PatchMapping("/tickets/{id}")
    public TicketRow patch(@PathVariable long id, @RequestBody TicketPatch p, HttpServletRequest r) {
        TicketRow t = store.ticket(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String cat = p.category() == null ? t.category() : Category.parse(p.category(), null) == null ? bad("category") : p.category().toUpperCase();
        String pri = p.priority() == null ? t.priority() : PRIORITIES.contains(p.priority()) ? p.priority() : bad("priority");
        String st = p.status() == null ? t.status() : STATUSES.contains(p.status()) ? p.status() : bad("status");
        String as = p.assignee() == null ? t.assignee() : p.assignee().isBlank() ? null : p.assignee().trim();
        store.updateTicket(id, cat, pri, st, as);
        store.audit(who(r).username(), "UPDATE_TICKET", id, null, "category " + t.category() + "->" + cat + ", priority "
                + t.priority() + "->" + pri + ", status " + t.status() + "->" + st + ", assignee " + t.assignee() + "->" + as);
        hub.publish("ticket", Map.of("id", id, "state", "updated"));
        return store.ticket(id).orElseThrow();
    }

    private static String bad(String field) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + field); }

    // ---------- non-ticket mail ----------
    /** disposition: SECURITY_HOLD, QUARANTINED, NO_TICKET, FAILED */
    @GetMapping("/emails")
    public List<EmailRow> emails(@RequestParam(required = false) String disposition) {
        return disposition == null ? store.recentEmails(200) : store.emailsByDisposition(disposition);
    }

    @GetMapping("/emails/{id}")
    public Map<String, Object> email(@PathVariable long id) {
        EmailRow e = store.email(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return Map.of("email", e, "events", store.eventsForEmail(id));
    }

    @PostMapping("/emails/{id}/release")
    public Map<String, Object> release(@PathVariable long id, HttpServletRequest r) {
        requireApprover(r);
        Optional<Long> t = pipeline.release(id, who(r).username());
        return t.<Map<String, Object>>map(v -> Map.of("ticketId", v))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "email cannot be released"));
    }

    @PostMapping("/emails/{id}/dismiss")
    public Map<String, Object> dismiss(@PathVariable long id, HttpServletRequest r) {
        requireApprover(r);
        EmailRow e = store.email(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        store.markEmailProcessed(id, "PROCESSED", e.verdict(), "dismissed by " + who(r).username(), e.category(), "DISMISSED", null);
        store.audit(who(r).username(), "DISMISS_EMAIL", null, id, "was " + e.disposition());
        hub.publish("email", Map.of("id", id, "state", "dismissed"));
        return Map.of("ok", true);
    }

    // ---------- drafts ----------
    public record DraftEdit(String body) {}

    @PutMapping("/drafts/{id}")
    public DraftRow editDraft(@PathVariable long id, @RequestBody DraftEdit e, HttpServletRequest r) {
        if (e.body() == null || e.body().isBlank() || e.body().length() > 5000) bad("body");
        store.updateDraftBody(id, e.body());
        DraftRow d = store.draft(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        store.audit(who(r).username(), "EDIT_DRAFT", d.ticketId(), null, "draft " + id);
        return d;
    }

    @PostMapping("/drafts/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id, HttpServletRequest r) {
        requireApprover(r);
        DraftRow d = store.draft(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        TicketRow t = store.ticket(d.ticketId()).orElseThrow();
        String hash = sha256(d.body());
        if (!store.decideDraft(id, "SENT", who(r).username(), hash))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "draft already decided");
        String mode;
        try {
            mode = outbox.send(id, t.requester(), t.subject(), d.body());
        } catch (RuntimeException ex) {
            store.audit(who(r).username(), "SEND_FAILED", t.id(), null, "draft " + id + ": " + ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "send failed: " + ex.getMessage());
        }
        store.audit(who(r).username(), "APPROVE_SEND", t.id(), null, "draft " + id + " to " + t.requester() + " sha256=" + hash + " mode=" + mode);
        hub.publish("ticket", Map.of("id", t.id(), "state", "updated"));
        return Map.of("sent", true, "mode", mode, "recipient", t.requester());
    }

    @PostMapping("/drafts/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, HttpServletRequest r) {
        requireApprover(r);
        DraftRow d = store.draft(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!store.decideDraft(id, "REJECTED", who(r).username(), sha256(d.body())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "draft already decided");
        store.audit(who(r).username(), "REJECT_DRAFT", d.ticketId(), null, "draft " + id);
        hub.publish("ticket", Map.of("id", d.ticketId(), "state", "updated"));
        return Map.of("ok", true);
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------- ingest, stats, stream ----------
    @PostMapping("/ingest/samples")
    public Map<String, Object> ingestSamples() {
        return Map.of("ingested", samples.load());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketsByCategory", store.countBy("tickets", "category"));
        out.put("ticketsByPriority", store.countBy("tickets", "priority"));
        out.put("emailsByDisposition", store.countBy("emails", "disposition"));
        out.put("emailsByVerdict", store.countBy("emails", "verdict"));
        out.put("avgLatencyMsByStage", store.avgLatencyByStage());
        Map<String, Object> p = new LinkedHashMap<>();
        for (String stage : List.of("GATE", "CLASSIFY", "DRAFT")) {
            List<Long> l = store.latenciesForStage(stage);
            if (!l.isEmpty()) p.put(stage, Map.of("p50", l.get(l.size() / 2), "p95", l.get(Math.min(l.size() - 1, (int) Math.floor(l.size() * 0.95))), "n", l.size()));
        }
        out.put("latencyPercentilesMs", p);
        out.put("llm", Map.of("enabled", llm.enabled(), "provider", llm.provider(), "model", llm.model(), "circuitOpen", llm.circuitOpen()));
        out.put("sendMode", outbox.mode());
        out.put("knowledgeBase", Map.of("chunks", kb.size(), "embedder", String.valueOf(kb.embedderId())));
        return out;
    }

    @GetMapping("/eval")
    public Map<String, Object> eval() throws Exception { return evaluator.run(); }

    @GetMapping("/stream")
    public SseEmitter stream() { return hub.subscribe(); }
}
