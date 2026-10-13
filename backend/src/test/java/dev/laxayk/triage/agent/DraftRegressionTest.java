package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.laxayk.triage.domain.ParsedEmail;
import dev.laxayk.triage.pipeline.Pipeline;
import dev.laxayk.triage.store.Store;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproducible draft regression set. The "model" is a synthetic provider that speaks the Groq/OpenAI tool-calling format and returns
 * fixed replies per scenario: it tests the app's own handling (retrieval, handoff, claim check, review flag, gap log, regeneration), not how
 * good a real model is. Real model quality is only measured by running the live app against Groq.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:draftreg;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=groq", "app.llm.api-key=gsk_test_0000000000", "app.llm.min-interval-ms=0"})
class DraftRegressionTest {
    static final ObjectMapper M = new ObjectMapper();
    static HttpServer server;

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", ex -> {
                JsonNode req = M.readTree(ex.getRequestBody().readAllBytes());
                String user = req.path("messages").get(1).path("content").asText("");
                String out;
                if (!req.has("tools")) {
                    out = "{\"choices\":[{\"message\":{\"content\":\"{\\\"category\\\":\\\"QUERY\\\",\\\"priority\\\":\\\"P3\\\",\\\"summary\\\":\\\"question\\\",\\\"confidence\\\":0.9}\"}}]}";
                } else if (!user.contains("Tool results so far")) {
                    String subject = user.lines().filter(l -> l.startsWith("Subject: ")).findFirst().orElse("Subject: ").substring(9);
                    out = call("search_knowledge", "{\"query\":\"" + subject + "\",\"scope\":\"docs\"}");
                } else if (user.contains("SCENARIO-REALTIME")) {
                    out = call("final_reply", "{\"reply\":\"Hi, FlowSync syncs in real time and supports Snowflake.\\n\\nFlowSync Support\",\"sources\":[],\"needs_human\":false}");
                } else if (user.contains("SCENARIO-UPTIME")) {
                    out = call("final_reply", "{\"reply\":\"Hi, replays run on a 99.9% uptime guarantee.\\n\\nFlowSync Support\",\"sources\":[],\"needs_human\":false}");
                } else if (user.contains("SCENARIO-PIZZA")) {
                    out = call("final_reply", "{\"reply\":\"Try Luigi's on 5th street.\",\"sources\":[],\"needs_human\":false}");
                } else {
                    out = call("final_reply", "{\"reply\":\"Hi, failed rows are kept for 14 days and can be replayed from the run.\\n\\nFlowSync Support\",\"sources\":[\"Replaying failed rows\"],\"needs_human\":false}");
                }
                byte[] b = out.getBytes();
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            server.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    static String call(String name, String args) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"" + args.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}]}}]}";
    }

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { r.add("app.llm.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort()); }
    @AfterAll static void stop() { server.stop(0); }

    @Autowired Pipeline pipeline;
    @Autowired Store store;

    private long ticketFor(String id, String subject, String body) {
        pipeline.ingest(new ParsedEmail("<" + id + "@t>", null, null, "dana@northwind-retail.example", "Dana Whitfield", "support@flowsync.example",
                subject, body, Instant.now(), false, List.of()), "test");
        assertTrue(pipeline.await(15000));
        long t = store.recentEmails(100).stream().filter(e -> e.messageId().equals("<" + id + "@t>")).findFirst().orElseThrow().ticketId();
        waitForDraftEvent(t, 1);
        return t;
    }

    /** The email is marked processed before the draft is written, so wait for the DRAFT event. */
    private void waitForDraftEvent(long tid, int count) {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (events(tid).stream().filter(e -> e.startsWith("DRAFT: ")).count() >= count) return;
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        fail("no DRAFT event: " + events(tid));
    }

    private List<String> events(long tid) {
        return store.emailsForTicket(tid).stream().flatMap(e -> store.eventsForEmail(e.id()).stream()).map(e -> e.stage() + ": " + e.detail()).toList();
    }

    @Test void supportedReplyIsNotFlagged() {
        long t = ticketFor("ok", "How long are failed rows kept?", "Hi, how long are failed rows kept after a timeout?");
        var d = store.draftsForTicket(t).get(0);
        assertTrue(d.generatedBy().startsWith("agent:"), d.generatedBy());
        assertFalse(store.ticket(t).orElseThrow().needsReview());
        assertTrue(events(t).stream().noneMatch(e -> e.startsWith("DRAFT_CHECK") || e.startsWith("KB_GAP")), events(t).toString());
    }

    @Test void unsupportedRealTimeClaimIsFlaggedAndKept() {
        long t = ticketFor("rt", "Does the sync run often?", "How often does the sync run? SCENARIO-REALTIME");
        var d = store.draftsForTicket(t).get(0);
        assertTrue(d.body().contains("real time"), "reply text is kept");
        assertTrue(store.ticket(t).orElseThrow().needsReview());
        assertTrue(events(t).stream().anyMatch(e -> e.startsWith("DRAFT_CHECK") && e.contains("real time") && e.contains("snowflake")), events(t).toString());
    }

    @Test void inventedNumbersAndGuaranteesAreFlagged() {
        long t = ticketFor("up", "How long are failed rows kept?", "How long are failed rows kept? SCENARIO-UPTIME");
        assertTrue(events(t).stream().anyMatch(e -> e.startsWith("DRAFT_CHECK") && e.contains("99.9") && e.contains("uptime")), events(t).toString());
    }

    @Test void questionNoDocumentAnswersGetsAHandoffAndALoggedGap() {
        long t = ticketFor("pz", "Pizza place nearby", "Can you recommend a pizza place nearby? SCENARIO-PIZZA");
        var d = store.draftsForTicket(t).get(0);
        assertFalse(d.body().contains("Luigi"));
        assertTrue(d.body().contains("teammate will follow up"));
        assertTrue(store.ticket(t).orElseThrow().needsReview());
        assertTrue(events(t).stream().anyMatch(e -> e.startsWith("KB_GAP")), events(t).toString());
    }

    @Test void regenerateKeepsTheSameChecksAndLeavesOneOpenDraft() {
        long t = ticketFor("rg", "Does the sync run often?", "How often does the sync run? SCENARIO-REALTIME");
        assertTrue(pipeline.regenerate(t, "tester"));
        var drafts = store.draftsForTicket(t);
        assertEquals(2, drafts.size());
        assertEquals(1, drafts.stream().filter(x -> x.status().equals("DRAFT")).count());
        assertEquals(2, events(t).stream().filter(e -> e.startsWith("DRAFT_CHECK")).count(), "the claim check ran again on the new draft");
    }
}
