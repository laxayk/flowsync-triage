package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Embeddings through any OpenAI-compatible /embeddings endpoint (Ollama: nomic-embed-text, OpenAI: text-embedding-3-small). */
public class HttpEmbedder implements Embedder {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String baseUrl, apiKey, model;

    public HttpEmbedder(ObjectMapper mapper, String baseUrl, String apiKey, String model) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String id() { return "http-" + model; }

    @Override
    public float[] embed(String text) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("model", model, "input", text))));
        if (!apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("embedding HTTP " + r.statusCode());
        JsonNode arr = mapper.readTree(r.body()).path("data").path(0).path("embedding");
        if (!arr.isArray() || arr.isEmpty()) throw new IllegalStateException("empty embedding");
        float[] v = new float[arr.size()];
        for (int i = 0; i < v.length; i++) v[i] = (float) arr.get(i).asDouble();
        return Embedder.normalize(v);
    }
}
