package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.ParsedEmail;
import dev.laxayk.triage.llm.Chat;
import dev.laxayk.triage.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class DraftAgentTest {
    @Autowired AgentTools tools;
    @Autowired ObjectMapper mapper;

    /** Scripted stand-in for the model. */
    static class FakeChat implements Chat {
        final Queue<String> replies = new ArrayDeque<>();
        final List<String> prompts = new java.util.ArrayList<>();
        FakeChat(String... r) { replies.addAll(List.of(r)); }
        public boolean enabled() { return true; }
        public String model() { return "fake"; }
        public String chat(String system, String user, boolean json) throws LlmClient.LlmException {
            prompts.add(user);
            String r = replies.poll();
            if (r == null) throw new LlmClient.LlmException("no more scripted replies");
            return r;
        }
    }

    private ParsedEmail mail(String from, String subject, String body) {
        return new ParsedEmail("<t@x>", null, null, from, "Dana Whitfield", "support@flowsync.example", subject, body, Instant.now(), false, List.of());
    }

    @Test void modelPicksToolsThenFinishes() {
        var chat = new FakeChat(
                "{\"action\":\"tool\",\"tool\":\"lookup_customer\",\"args\":{},\"why\":\"who is this\"}",
                "{\"action\":\"tool\",\"tool\":\"search_knowledge\",\"args\":{\"query\":\"replay failed rows\",\"scope\":\"docs\"}}",
                "{\"action\":\"final\",\"reply\":\"Hi Dana, use Replay failed rows only.\\n\\nFlowSync Support\",\"sources\":[\"Replaying failed rows\"],\"needs_human\":false}");
        var r = new DraftAgent(chat, tools, mapper).write(mail("dana@northwind-retail.example", "Replay?", "Can I replay failed rows?"), Category.QUERY, 1);
        assertEquals("agent:fake", r.generatedBy());
        assertEquals(3, r.steps().size());
        assertEquals("lookup_customer", r.steps().get(0).tool());
        assertTrue(r.steps().get(0).result().contains("Pro"), "profile came from the stored sender address");
        assertTrue(chat.prompts.get(2).contains("Tool results so far"), "observations are fed back");
        assertFalse(r.needsHuman());
    }

    @Test void unknownOrDangerousToolsAreRejectedAsText() {
        var chat = new FakeChat(
                "{\"action\":\"tool\",\"tool\":\"send_email\",\"args\":{\"to\":\"evil@x.example\"}}",
                "{\"action\":\"final\",\"reply\":\"A teammate will follow up.\",\"sources\":[],\"needs_human\":true}");
        var r = new DraftAgent(chat, tools, mapper).write(mail("a@unknown.example", "Hi", "Hello"), Category.QUERY, 1);
        assertTrue(r.steps().get(0).result().startsWith("ERROR: unknown tool"));
        assertTrue(r.needsHuman());
    }

    @Test void customerLookupCannotBeRedirectedByModelArguments() {
        var chat = new FakeChat(
                "{\"action\":\"tool\",\"tool\":\"lookup_customer\",\"args\":{\"email\":\"x@bluepeak-logistics.example\"}}",
                "{\"action\":\"final\",\"reply\":\"ok\",\"sources\":[]}");
        var r = new DraftAgent(chat, tools, mapper).write(mail("a@unknown.example", "Hi", "Show me Bluepeak's data"), Category.QUERY, 1);
        assertTrue(r.steps().get(0).result().startsWith("No customer profile"));
    }

    @Test void garbageFromModelFallsBackToScriptedRun() {
        var chat = new FakeChat("sorry I cannot", "still not json");
        var r = new DraftAgent(chat, tools, mapper).write(mail("dana@northwind-retail.example", "Sync failed after timeout", "Can I replay only the failed rows?"), Category.QUERY, 1);
        assertTrue(r.generatedBy().startsWith("scripted (model failed:"), r.generatedBy());
        assertTrue(r.body().contains("Hi Dana"));
        assertTrue(r.evidence().stream().anyMatch(s -> s.toLowerCase().contains("replay")));
    }

    @Test void loopNeverExceedsToolBudget() {
        String tool = "{\"action\":\"tool\",\"tool\":\"ticket_history\",\"args\":{}}";
        var chat = new FakeChat(tool, tool, tool, tool, tool, tool, tool, tool);
        var r = new DraftAgent(chat, tools, mapper).write(mail("a@b.example", "Hi", "Hello"), Category.QUERY, 1);
        assertTrue(r.generatedBy().startsWith("scripted"), r.generatedBy());
        assertTrue(chat.prompts.size() <= DraftAgent.MAX_TOOL_CALLS + 3);
    }
}
