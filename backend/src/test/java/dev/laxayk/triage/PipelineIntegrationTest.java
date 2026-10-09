package dev.laxayk.triage;

import dev.laxayk.triage.domain.Rows.EmailRow;
import dev.laxayk.triage.domain.Rows.TicketRow;
import dev.laxayk.triage.ingest.SampleLoader;
import dev.laxayk.triage.pipeline.Pipeline;
import dev.laxayk.triage.store.Store;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pipeline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class PipelineIntegrationTest {
    @Autowired SampleLoader samples;
    @Autowired Pipeline pipeline;
    @Autowired Store store;

    private Map<String, EmailRow> byId() {
        return store.recentEmails(100).stream().collect(Collectors.toMap(EmailRow::messageId, e -> e));
    }

    @Test
    void sampleMailboxEndsUpWhereItShould() {
        int n = samples.load();
        assertEquals(17, n, "18 files, one is a duplicate delivery of the same Message-ID and is skipped");
        assertTrue(pipeline.await(15000), "pipeline should drain");

        var m = byId();
        // Safety gate outcomes
        assertEquals("SECURITY_HOLD", m.get("<13-injection@samples.flowsync>").disposition());
        assertEquals("PROMPT_INJECTION", m.get("<13-injection@samples.flowsync>").verdict());
        assertEquals("SECURITY_HOLD", m.get("<12-phishing@samples.flowsync>").disposition());
        assertEquals("PHISHING", m.get("<12-phishing@samples.flowsync>").verdict());
        assertEquals("SECURITY_HOLD", m.get("<18-attachment@samples.flowsync>").disposition());
        assertEquals("QUARANTINED", m.get("<11-spam@samples.flowsync>").disposition());
        assertEquals("NO_TICKET", m.get("<09-out-of-office@samples.flowsync>").disposition());
        assertEquals("NO_TICKET", m.get("<10-sales@samples.flowsync>").disposition());
        assertEquals("NO_TICKET", m.get("<08-praise@samples.flowsync>").disposition());

        // Held mail never becomes a ticket
        for (String id : List.of("13-injection", "12-phishing", "11-spam", "10-sales", "09-out-of-office"))
            assertNull(m.get("<" + id + "@samples.flowsync>").ticketId(), id);

        // Ticket categories (keyword fallback, no LLM configured)
        assertEquals("OUTAGE", cat(m, "07-outage"));
        assertEquals("BILLING", cat(m, "05-billing"));
        assertEquals("ACCOUNT_ACCESS", cat(m, "06-access"));
        assertEquals("BUG", cat(m, "02-bug-duplicates"));
        assertEquals("WRONG_BEHAVIOUR", cat(m, "03-wrong-behaviour"));
        assertEquals("ENHANCEMENT", cat(m, "04-enhancement"));
        assertEquals("QUERY", cat(m, "01-query-replay"));

        // Priority and assignment
        TicketRow outage = ticket(m, "07-outage");
        assertEquals("P1", outage.priority());
        assertEquals("ravi", outage.assignee());
        assertTrue(outage.needsReview(), "fallback classification is flagged for human review");

        // Threading: the follow-up joins the original ticket instead of creating a new one
        EmailRow follow = m.get("<14-followup-reply@samples.flowsync>");
        assertEquals("THREADED", follow.disposition());
        assertEquals(m.get("<01-query-replay@samples.flowsync>").ticketId(), follow.ticketId());

        // Reply drafts exist for query-type tickets and cite KB evidence
        var drafts = store.draftsForTicket(follow.ticketId());
        assertEquals(1, drafts.size());
        assertTrue(drafts.get(0).evidence().toLowerCase().contains("replay"));
        assertEquals("DRAFT", drafts.get(0).status());

        // Drafting agent leaves a tool trace (no LLM here, so the scripted path ran the same tools)
        var steps = store.draftSteps(drafts.get(0).id());
        assertTrue(steps.stream().anyMatch(st -> "lookup_customer".equals(st.tool())));
        assertTrue(steps.stream().anyMatch(st -> "search_knowledge".equals(st.tool())));
        assertEquals("scripted", drafts.get(0).generatedBy());
        assertTrue(drafts.get(0).body().contains("Dana"));

        // Each ticket briefs its department owner exactly once, even after a threaded reply
        
        var mails = store.ownerMails(outage.id());
        assertEquals(1, mails.size());
        assertEquals("ravi@flowsync.example", mails.get(0).recipient());
        assertTrue(mails.get(0).body().contains("Veridiant"), "brief carries the customer profile");
        assertEquals("capture", mails.get(0).mode());
        assertEquals("priya@flowsync.example", store.ownerMails(ticket(m, "02-bug-duplicates").id()).get(0).recipient());
        assertEquals(1, store.ownerMails(follow.ticketId()).size());

        // Idempotent re-run: nothing new
        assertEquals(0, samples.load());
        long tickets = store.listTickets(null, null, null).size();
        assertEquals(tickets, store.listTickets(null, null, null).size());
    }

    private String cat(Map<String, EmailRow> m, String id) {
        return ticket(m, id).category();
    }

    private TicketRow ticket(Map<String, EmailRow> m, String id) {
        EmailRow e = m.get("<" + id + "@samples.flowsync>");
        assertNotNull(e, id);
        assertNotNull(e.ticketId(), id + " should have a ticket, was " + e.disposition() + " / " + e.verdictReason());
        return store.ticket(e.ticketId()).orElseThrow();
    }
}
