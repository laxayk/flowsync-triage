package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.ParsedEmail;
import dev.laxayk.triage.llm.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real LlmClient and DraftAgent against a local fake that speaks the OpenAI/Groq tool-calling wire format. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:native;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class NativeToolsTest {
    @Autowired AgentTools tools;
    @Autowired ObjectMapper mapper;
    HttpServer server;
    final List<JsonNode> requests = new ArrayList<>();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private LlmClient start(IntFunction<String[]> responder) throws Exception {
        AtomicInteger n = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            requests.add(mapper.readTree(ex.getRequestBody().readAllBytes()));
            String[] r = responder.apply(n.incrementAndGet());
            byte[] out = r[1].getBytes();
            ex.sendResponseHeaders(Integer.parseInt(r[0]), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new LlmClient(mapper, "groq", "http://127.0.0.1:" + server.getAddress().getPort(), "gsk_test_0000000000", "openai/gpt-oss-20b", 5, 1);
    }

    private static String toolCall(String name, String argsJson) {
        String a = argsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + a + "\"}}]}}]}";
    }

    private ParsedEmail mail(String subject, String body) {
        return new ParsedEmail("<t@x>", null, null, "dana@northwind-retail.example", "Dana", "support@flowsync.example", subject, body, Instant.now(), false, List.of());
    }

    @Test void agentRunsOnNativeFunctionCallingEndToEnd() throws Exception {
        LlmClient c = start(n -> switch (n) {
            case 1 -> new String[]{"200", toolCall("search_knowledge", "{\"query\":\"replay failed rows\",\"scope\":\"docs\"}")};
            case 2 -> new String[]{"200", toolCall("final_reply", "{\"reply\":\"Hi Dana, use Replay failed rows only.\\n\\nFlowSync Support\",\"sources\":[\"Replaying failed rows\"],\"needs_human\":false}")};
            default -> new String[]{"500", "{}"};
        });
        var r = new DraftAgent(c, tools, mapper).write(mail("Replay?", "Can I replay only failed rows?"), Category.QUERY, 1);
        assertEquals("agent:openai/gpt-oss-20b", r.generatedBy());
        assertEquals("search_knowledge", r.steps().get(0).tool());
        assertTrue(r.steps().get(0).result().contains("Replay"), r.steps().get(0).result());
        assertTrue(r.body().startsWith("Hi Dana"));
        assertFalse(r.needsHuman());
        JsonNode first = requests.get(0);
        assertTrue(first.path("tools").isArray() && first.path("tools").size() == 5, "four lookups plus final_reply are declared");
        assertEquals("auto", first.path("tool_choice").asText());
        assertTrue(first.path("response_format").isMissingNode(), "no JSON mode when tools are used");
        assertEquals("final_reply", first.path("tools").get(4).path("function").path("name").asText());
    }

    @Test void plainTextAnswerAfterLookupIsKeptButFlaggedForAHuman() throws Exception {
        LlmClient c = start(n -> n == 1
                ? new String[]{"200", toolCall("lookup_customer", "{}")}
                : new String[]{"200", "{\"choices\":[{\"message\":{\"content\":\"Hi Dana, a teammate will follow up.\\n\\nFlowSync Support\"}}]}"});
        var r = new DraftAgent(c, tools, mapper).write(mail("Hi", "Hello"), Category.QUERY, 1);
        assertEquals("agent:openai/gpt-oss-20b", r.generatedBy());
        assertTrue(r.needsHuman());
        assertTrue(r.body().contains("teammate will follow up"));
    }

    @Test void providerRejectedCallWithReadableFailedGenerationStillAdvancesTheLoop() throws Exception {
        String rejected = "{\"error\":{\"message\":\"Tool choice is none, but model called a tool\",\"code\":\"tool_use_failed\",\"failed_generation\":\"{\\\"name\\\":\\\"lookup_customer\\\",\\\"arguments\\\":{}}\"}}";
        LlmClient c = start(n -> switch (n) {
            case 1 -> new String[]{"400", rejected};
            case 2 -> new String[]{"200", toolCall("final_reply", "{\"reply\":\"ok\\n\\nFlowSync Support\",\"needs_human\":true}")};
            default -> new String[]{"500", "{}"};
        });
        var r = new DraftAgent(c, tools, mapper).write(mail("Hi", "Hello"), Category.QUERY, 1);
        assertEquals("agent:openai/gpt-oss-20b", r.generatedBy());
        assertEquals("lookup_customer", r.steps().get(0).tool());
    }

    @Test void outputParseFailedOnJsonModeIsRetriedWithoutJsonMode() throws Exception {
        String bad = "{\"error\":{\"message\":\"Parsing failed. The model generated output that could not be parsed.\",\"type\":\"invalid_request_error\",\"code\":\"output_parse_failed\"}}";
        LlmClient c = start(n -> n == 1 ? new String[]{"400", bad} : new String[]{"200", "{\"choices\":[{\"message\":{\"content\":\"{\\\"category\\\":\\\"QUERY\\\"}\"}}]}"});
        assertTrue(c.chat("s", "u", true).contains("QUERY"));
        assertTrue(requests.get(0).has("response_format"));
        assertFalse(requests.get(1).has("response_format"));
    }
}
