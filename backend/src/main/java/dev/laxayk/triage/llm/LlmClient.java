package dev.laxayk.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client for every provider: Groq, OpenAI and Ollama all speak the OpenAI chat-completions protocol,
 * so "swappable provider" is just base URL + model + key. Includes a small circuit breaker and a concurrency cap
 * so a rate-limited free tier degrades to the heuristic fallback instead of stalling the pipeline.
 */
@Component
public class LlmClient implements Chat {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    public static class LlmException extends Exception {
        public LlmException(String m) { super(m); this.outage = true; }
        public LlmException(String m, Throwable t) { super(m, t); this.outage = true; }
        /** False for rejections of one request (bad request, rate limit): they say nothing about the provider being down. */
        final boolean outage;
        private LlmException(String m, boolean outage) { super(m); this.outage = outage; }
        static LlmException rejected(String m) { return new LlmException(m, false); }
    }

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String provider, baseUrl, apiKey, model;
    private final int timeoutSeconds;
    private final Semaphore concurrency;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();
    private volatile String lastError = "";

    public LlmClient(ObjectMapper mapper,
                     @Value("${app.llm.provider:none}") String provider,
                     @Value("${app.llm.base-url:}") String baseUrl,
                     @Value("${app.llm.api-key:}") String apiKey,
                     @Value("${app.llm.model:}") String model,
                     @Value("${app.llm.timeout-seconds:25}") int timeoutSeconds,
                     @Value("${app.llm.max-concurrency:3}") int maxConcurrency) {
        this.mapper = mapper;
        this.provider = provider.toLowerCase();
        this.baseUrl = baseUrl.isBlank() ? defaultBase(this.provider) : baseUrl;
        this.model = model.isBlank() ? defaultModel(this.provider) : model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.concurrency = new Semaphore(Math.max(1, maxConcurrency));
    }

    private static String defaultBase(String p) {
        return switch (p) {
            case "groq" -> "https://api.groq.com/openai/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "ollama" -> "http://localhost:11434/v1";
            default -> "";
        };
    }

    private static String defaultModel(String p) {
        return switch (p) {
            case "groq" -> "openai/gpt-oss-20b";
            case "openai" -> "gpt-4o-mini";
            case "ollama" -> "qwen2.5:3b";
            default -> "";
        };
    }

    @Override
    public boolean enabled() {
        if (provider.equals("none") || baseUrl.isBlank()) return false;
        return provider.equals("ollama") || !apiKey.isBlank();
    }

    /** Most recent failure reason (already redacted) or empty; shown on the Stats tab so a fallback is never a mystery. */
    public String lastError() { return lastError; }

    public boolean circuitOpen() { return System.currentTimeMillis() < openUntil.get(); }

    public String provider() { return provider; }

    @Override
    public String model() { return model; }

    /** Returns the assistant message content. Throws LlmException on any failure so callers can fall back. */
    @Override
    public String chat(String system, String user, boolean json) throws LlmException {
        if (!enabled()) throw new LlmException("LLM not configured");
        if (circuitOpen()) throw new LlmException("LLM circuit open, skipping call");
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
        try {
            String out = callWithRetry(system, user, json);
            consecutiveFailures.set(0);
            lastError = "";
            return out;
        } catch (LlmException e) {
            lastError = e.getMessage() == null ? "" : e.getMessage();
            if (e.outage && consecutiveFailures.incrementAndGet() >= 3) {
                openUntil.set(System.currentTimeMillis() + 60_000);
                consecutiveFailures.set(0);
                log.warn("LLM circuit opened for 60s after 3 failures in a row, last: {}", e.getMessage());
            }
            throw e;
        } finally {
            concurrency.release();
        }
    }

    private String callWithRetry(String system, String user, boolean json) throws LlmException {
        LlmException last = null;
        boolean jsonMode = json;
        String sys = system;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return call(sys, user, jsonMode);
            } catch (RetryableException e) {
                last = e.outage ? new LlmException(e.getMessage()) : LlmException.rejected(e.getMessage());
                if (e.nudge && !sys.endsWith(NUDGE)) {
                    sys = sys + NUDGE;
                    log.warn("LLM tried native tool calling ({}), retrying once with a reminder", e.getMessage());
                    continue;
                }
                if (e.dropJsonMode && jsonMode) {
                    // the provider rejected its own JSON mode on this output; our parsers accept plain text with a JSON object in it
                    jsonMode = false;
                    log.warn("LLM rejected json mode ({}), retrying once without it", e.getMessage());
                    continue;
                }
                try { Thread.sleep(e.waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw last == null ? new LlmException("LLM call failed") : last;
    }

    /** Some models (gpt-oss on Groq) answer with their native tool-call format even though no tools are declared; Groq rejects that with 400 tool_use_failed. */
    static final String NUDGE = "\n\nImportant: do not use native function or tool calling. Reply with exactly one JSON object as text, using the action format above.";

    private static class RetryableException extends Exception {
        final long waitMs;
        final boolean dropJsonMode, nudge, outage;
        RetryableException(String m, long waitMs, boolean outage) { this(m, waitMs, false, false, outage); }
        RetryableException(String m, long waitMs, boolean dropJsonMode, boolean nudge, boolean outage) {
            super(m); this.waitMs = waitMs; this.dropJsonMode = dropJsonMode; this.nudge = nudge; this.outage = outage;
        }
    }

    /**
     * Turns the model's rejected native tool call (Groq puts it in error.failed_generation) into our own
     * {"action":"tool",...} message, so the agent loop carries on instead of falling back to the template. Null if unreadable.
     */
    String salvageToolCall(String failedGeneration) {
        if (failedGeneration == null || failedGeneration.isBlank()) return null;
        try {
            String tool = null;
            JsonNode args = null;
            int a = failedGeneration.indexOf('{');
            while (a >= 0 && tool == null) {
                int b = failedGeneration.lastIndexOf('}');
                for (int end = b; end > a && tool == null; end = failedGeneration.lastIndexOf('}', end - 1)) {
                    try {
                        JsonNode n = mapper.readTree(failedGeneration.substring(a, end + 1));
                        JsonNode name = n.path("name"), ar = n.has("arguments") ? n.path("arguments") : n.has("parameters") ? n.path("parameters") : n.path("args");
                        if (name.isTextual() && !ar.isMissingNode()) {
                            tool = name.asText();
                            args = ar.isTextual() ? mapper.readTree(ar.asText()) : ar;
                        }
                    } catch (Exception ignored) { /* try a shorter slice */ }
                }
                a = failedGeneration.indexOf('{', a + 1);
            }
            if (tool == null) {
                var m = java.util.regex.Pattern.compile("functions\\.([A-Za-z_][A-Za-z0-9_]*)[^{]*(\\{.*\\})", java.util.regex.Pattern.DOTALL).matcher(failedGeneration);
                if (m.find()) { tool = m.group(1); args = mapper.readTree(m.group(2)); }
            }
            if (tool == null || args == null || !args.isObject()) return null;
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("action", "tool"); out.put("tool", tool); out.put("args", args); out.put("why", "recovered from the provider's rejected tool call");
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** "LLM HTTP 400: <provider error message>", clipped and with anything key-shaped removed. The request is never included. */
    String describeError(int status, String body) {
        String msg = "";
        try {
            JsonNode err = mapper.readTree(body).path("error");
            msg = err.isTextual() ? err.asText() : err.path("code").asText("") + " " + err.path("message").asText("");
        } catch (Exception ignored) {
            // non-JSON error body: fall through with no detail
        }
        msg = msg.trim();
        if (!apiKey.isBlank()) msg = msg.replace(apiKey, "[redacted]");
        msg = msg.replaceAll("(gsk_|sk-)[A-Za-z0-9_\\-]{8,}", "[redacted]").replaceAll("\\s+", " ");
        if (msg.length() > 240) msg = msg.substring(0, 240) + "...";
        return "LLM HTTP " + status + (msg.isEmpty() ? "" : ": " + msg);
    }

    static long retryAfterMs(java.util.Optional<String> header) {
        return header.map(v -> {
            try { return Math.max(500L, Math.min(20_000L, (long) (Double.parseDouble(v.trim()) * 1000))); }
            catch (NumberFormatException e) { return 1500L; }
        }).orElse(1500L);
    }

    private String call(String system, String user, boolean json) throws LlmException, RetryableException {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("messages", List.of(Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)));
            if (json) body.put("response_format", Map.of("type", "json_object"));
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 429 || sc >= 500) {
                throw new RetryableException(describeError(sc, resp.body()), retryAfterMs(resp.headers().firstValue("retry-after")), sc != 429);
            }
            if (sc == 400 && resp.body().contains("tool_use_failed")) {
                String fg = null;
                try { fg = mapper.readTree(resp.body()).path("error").path("failed_generation").asText(null); } catch (Exception ignored) { /* no detail */ }
                String salvaged = salvageToolCall(fg);
                if (salvaged != null) { log.info("LLM tool_use_failed: recovered the model's tool call"); return salvaged; }
                throw new RetryableException(describeError(sc, resp.body()), 0, false, true, false);
            }
            if (sc == 400 && json && resp.body().contains("json_validate_failed")) {
                throw new RetryableException(describeError(sc, resp.body()), 0, true, false, false);
            }
            if (sc >= 400) throw LlmException.rejected(describeError(sc, resp.body()));
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) throw new LlmException("empty LLM response");
            return content.asText();
        } catch (java.io.IOException e) {
            throw new LlmException("LLM I/O error: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
    }
}
