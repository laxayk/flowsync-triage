package dev.laxayk.triage.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientErrorTest {
    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private static final String KEY = "gsk_TESTKEY1234567890abcdef";

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private LlmClient start(java.util.function.Function<Integer, String[]> responder) throws Exception {
        AtomicInteger n = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes()));
            String[] r = responder.apply(n.incrementAndGet()); // {status, body, retryAfter?}
            byte[] out = r[1].getBytes();
            if (r.length > 2) ex.getResponseHeaders().add("Retry-After", r[2]);
            ex.sendResponseHeaders(Integer.parseInt(r[0]), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new LlmClient(new ObjectMapper(), "groq", "http://127.0.0.1:" + server.getAddress().getPort(), KEY, "m", 5, 1);
    }

    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"{\\\"a\\\":1}\"}}]}";

    @Test void errorMessageNamesTheProviderReasonButNeverTheKeyOrRequest() throws Exception {
        LlmClient c = start(n -> new String[]{"400", "{\"error\":{\"message\":\"model bad " + KEY + " request\",\"code\":\"invalid_request\"}}"});
        var ex = assertThrows(LlmClient.LlmException.class, () -> c.chat("sys", "user text SECRET-EMAIL-BODY", true));
        assertTrue(ex.getMessage().startsWith("LLM HTTP 400: invalid_request model bad"), ex.getMessage());
        assertFalse(ex.getMessage().contains(KEY));
        assertFalse(ex.getMessage().contains("SECRET-EMAIL-BODY"));
    }

    @Test void jsonValidateFailedIsRetriedOnceWithoutJsonMode() throws Exception {
        LlmClient c = start(n -> n == 1
                ? new String[]{"400", "{\"error\":{\"message\":\"Failed to generate JSON\",\"code\":\"json_validate_failed\"}}"}
                : new String[]{"200", OK});
        assertEquals("{\"a\":1}", c.chat("s", "u", true));
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).contains("response_format"));
        assertFalse(bodies.get(1).contains("response_format"));
    }

    @Test void otherBadRequestsAreNotRetried() throws Exception {
        LlmClient c = start(n -> new String[]{"400", "{\"error\":{\"message\":\"nope\",\"code\":\"x\"}}"});
        assertThrows(LlmClient.LlmException.class, () -> c.chat("s", "u", true));
        assertEquals(1, bodies.size());
    }

    @Test void rateLimitRetriesAfterTheProvidersWait() throws Exception {
        LlmClient c = start(n -> n == 1 ? new String[]{"429", "{\"error\":{\"message\":\"Rate limit reached, try again in 1s\"}}", "1"} : new String[]{"200", OK});
        long t = System.currentTimeMillis();
        assertEquals("{\"a\":1}", c.chat("s", "u", true));
        assertTrue(System.currentTimeMillis() - t >= 900);
        assertEquals(2, bodies.size());
    }

    @Test void retryAfterParsing() {
        assertEquals(7200, LlmClient.retryAfterMs(Optional.of("7.2")));
        assertEquals(20_000, LlmClient.retryAfterMs(Optional.of("300")));
        assertEquals(1500, LlmClient.retryAfterMs(Optional.empty()));
        assertEquals(1500, LlmClient.retryAfterMs(Optional.of("soon")));
    }
}
