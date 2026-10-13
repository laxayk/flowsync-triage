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
        public LlmException(String m) { super(m); }
        public LlmException(String m, Throwable t) { super(m, t); }
    }

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String provider, baseUrl, apiKey, model;
    private final int timeoutSeconds;
    private final Semaphore concurrency;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

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
            return out;
        } catch (LlmException e) {
            if (consecutiveFailures.incrementAndGet() >= 3) {
                openUntil.set(System.currentTimeMillis() + 60_000);
                consecutiveFailures.set(0);
                log.warn("LLM circuit opened for 60s after repeated failures: {}", e.getMessage());
            }
            throw e;
        } finally {
            concurrency.release();
        }
    }

    private String callWithRetry(String system, String user, boolean json) throws LlmException {
        LlmException last = null;
        boolean jsonMode = json;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return call(system, user, jsonMode);
            } catch (RetryableException e) {
                last = new LlmException(e.getMessage());
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

    private static class RetryableException extends Exception {
        final long waitMs;
        final boolean dropJsonMode;
        RetryableException(String m, long waitMs) { this(m, waitMs, false); }
        RetryableException(String m, long waitMs, boolean dropJsonMode) { super(m); this.waitMs = waitMs; this.dropJsonMode = dropJsonMode; }
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
                throw new RetryableException(describeError(sc, resp.body()), retryAfterMs(resp.headers().firstValue("retry-after")));
            }
            if (sc == 400 && json && resp.body().contains("json_validate_failed")) {
                throw new RetryableException(describeError(sc, resp.body()), 0, true);
            }
            if (sc >= 400) throw new LlmException(describeError(sc, resp.body()));
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
