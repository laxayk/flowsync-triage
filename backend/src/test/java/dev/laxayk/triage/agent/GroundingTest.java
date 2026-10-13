package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.ParsedEmail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:grounding;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class GroundingTest {
    @Autowired AgentTools tools;
    @Autowired ObjectMapper mapper;

    private ParsedEmail mail(String subject, String body) {
        return new ParsedEmail("<g@x>", null, null, "dana@northwind-retail.example", "Dana Whitfield", "support@flowsync.example", subject, body, Instant.now(), false, List.of());
    }

    private static String search(String q) { return "{\"action\":\"tool\",\"tool\":\"search_knowledge\",\"args\":{\"query\":\"" + q + "\",\"scope\":\"docs\"}}"; }

    @Test void claimNotInTheDocumentsFlagsTheDraftForAPerson() {
        var chat = new DraftAgentTest.FakeChat(search("how often does the sync run"),
                "{\"action\":\"final\",\"reply\":\"Hi Dana, FlowSync syncs in real time and also supports Snowflake.\\n\\nFlowSync Support\",\"sources\":[],\"needs_human\":false}");
        var r = new DraftAgent(chat, tools, mapper).write(mail("Services?", "how often does the sync run"), Category.QUERY, 1);
        assertTrue(r.needsHuman());
        assertTrue(r.warnings().get(0).contains("real time") && r.warnings().get(0).contains("snowflake"), r.warnings().toString());
        assertTrue(r.body().contains("real time"), "the reply text is kept for the reviewer, not silently edited");
    }

    @Test void supportedClaimsPass() {
        var chat = new DraftAgentTest.FakeChat(search("how often does the sync run on pro plan"),
                "{\"action\":\"final\",\"reply\":\"Hi Dana, on Pro a sync can run every 5 minutes. It is scheduled, not real time.\\n\\nFlowSync Support\",\"sources\":[\"Sync schedules\"],\"needs_human\":false}");
        var r = new DraftAgent(chat, tools, mapper).write(mail("Schedule", "how often does the sync run on pro plan"), Category.QUERY, 1);
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("number 5")), r.warnings().toString());
    }

    @Test void noDocumentAnswersMeansAHandoffNotAnImprovisedReply() {
        var chat = new DraftAgentTest.FakeChat(search("pizza place nearby"),
                "{\"action\":\"final\",\"reply\":\"Try Luigi's on 5th street.\",\"sources\":[],\"needs_human\":false}");
        var r = new DraftAgent(chat, tools, mapper).write(mail("Pizza", "pizza place nearby"), Category.QUERY, 1);
        assertTrue(r.gap());
        assertTrue(r.needsHuman());
        assertTrue(r.body().contains("a teammate will follow up"));
        assertFalse(r.body().contains("Luigi"));
    }

    @Test void heuristicLimitIsExplicit() {
        // A wrong claim built only from words that do appear in the documents is not caught: the check is a heuristic, not proof.
        var bad = DraftAgent.unsupportedClaims("Replays are kept for 14 days and run on Salesforce", "Failed rows are kept for 14 days. Salesforce connector.", "");
        assertTrue(bad.isEmpty());
    }
}
