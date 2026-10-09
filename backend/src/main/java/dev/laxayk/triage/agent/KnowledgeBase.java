package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.laxayk.triage.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The vector store the agents search: product docs, client profiles and internal process notes.
 * Chunks (one per markdown file) are embedded once and persisted in the kb_chunks table together with the embedder id and a
 * content hash, so restarts only re-embed what changed. Search is cosine similarity in memory, which is the right size for
 * a few hundred chunks. With app.embed.pgvector=true on Postgres the same rows are also written to a pgvector column and
 * searched by the database (exact cosine scan, no index needed at this size). Any pgvector error falls back to the in-memory search.
 */
@Service
public class KnowledgeBase {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    public record Chunk(String source, String type, String title, String text, Map<String, String> fields, float[] vector) {}
    public record Hit(Chunk chunk, double score) {}

    private final Store store;
    private final Embedder embedder;
    private final List<Chunk> chunks = new CopyOnWriteArrayList<>();
    private volatile String activeEmbedder;
    private final boolean wantPg;
    private volatile boolean pgOn;

    public KnowledgeBase(Store store, ObjectMapper mapper,
                         @Value("${app.embed.provider:hash}") String provider,
                         @Value("${app.embed.base-url:}") String baseUrl,
                         @Value("${app.embed.api-key:}") String apiKey,
                         @Value("${app.embed.model:}") String model,
                         @Value("${app.embed.pgvector:false}") boolean pgvector) {
        this.wantPg = pgvector;
        this.store = store;
        if (!provider.equals("hash") && !model.isBlank()) {
            String base = baseUrl.isBlank() ? (provider.equals("openai") ? "https://api.openai.com/v1" : "http://localhost:11434/v1") : baseUrl;
            this.embedder = new HttpEmbedder(mapper, base, apiKey, model);
        } else {
            this.embedder = new Embedder.Hashing();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(0)
    public void indexOnStart() {
        reindex();
    }

    public synchronized void reindex() {
        Embedder active = embedder;
        List<Chunk> fresh = new ArrayList<>();
        try {
            Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath:/kb/**/*.md");
            Arrays.sort(all, Comparator.comparing(r -> String.valueOf(r.getDescription())));
            int embedded = 0, reused = 0;
            for (Resource r : all) {
                String path = r.getURL().toString();
                String rel = path.substring(path.indexOf("/kb/") + 4);
                String type = rel.startsWith("clients/") ? "client" : rel.startsWith("processes/") ? "process" : "doc";
                String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String title = text.lines().findFirst().orElse(rel).replaceFirst("^#\\s*", "");
                String sha = sha(text);
                float[] vec = null;
                var cached = store.kbChunk(rel);
                if (cached.isPresent() && cached.get().sha().equals(sha) && cached.get().embedder().equals(active.id())) {
                    vec = parse(cached.get().vector());
                    reused++;
                }
                if (vec == null) {
                    try {
                        vec = active.embed(title + "\n" + text);
                    } catch (Exception e) {
                        log.warn("Embedder {} failed ({}), switching to local hashing for the whole index", active.id(), e.getMessage());
                        active = new Embedder.Hashing();
                        fresh.clear();
                        reindexWith(active, all);
                        return;
                    }
                    store.upsertKbChunk(rel, type, title, sha, active.id(), format(vec));
                    embedded++;
                }
                fresh.add(new Chunk(rel, type, title, text, fields(text), vec));
            }
            chunks.clear();
            chunks.addAll(fresh);
            activeEmbedder = active.id();
            syncPg(fresh);
            log.info("Knowledge base ready: {} chunks ({} embedded, {} reused) with {}", fresh.size(), embedded, reused, active.id());
        } catch (Exception e) {
            log.error("Knowledge base indexing failed", e);
        }
    }

    private void reindexWith(Embedder active, Resource[] all) throws Exception {
        List<Chunk> fresh = new ArrayList<>();
        for (Resource r : all) {
            String path = r.getURL().toString();
            String rel = path.substring(path.indexOf("/kb/") + 4);
            String type = rel.startsWith("clients/") ? "client" : rel.startsWith("processes/") ? "process" : "doc";
            String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String title = text.lines().findFirst().orElse(rel).replaceFirst("^#\\s*", "");
            float[] vec = active.embed(title + "\n" + text);
            store.upsertKbChunk(rel, type, title, sha(text), active.id(), format(vec));
            fresh.add(new Chunk(rel, type, title, text, fields(text), vec));
        }
        chunks.clear();
        chunks.addAll(fresh);
        activeEmbedder = active.id();
        syncPg(fresh);
    }

    private void syncPg(List<Chunk> list) {
        if (!wantPg) return;
        try {
            store.enablePgVector();
            for (Chunk c : list) store.setEmbedding(c.source(), "[" + format(c.vector()) + "]");
            pgOn = true;
            log.info("pgvector search enabled for {} chunks", list.size());
        } catch (Exception e) {
            pgOn = false;
            log.warn("pgvector unavailable ({}), searching in memory instead", e.getMessage());
        }
    }

    public boolean pgvectorActive() { return pgOn; }

    /** Semantic-ish search. type is "doc", "client", "process" or null for everything. */
    public List<Hit> search(String query, String type, int k) {
        float[] q;
        try {
            q = (activeEmbedder != null && activeEmbedder.startsWith("http-") ? embedder : new Embedder.Hashing()).embed(query);
        } catch (Exception e) {
            q = new Embedder.Hashing().embed(query);
        }
        final float[] qv = q;
        if (pgOn) {
            try {
                Map<String, Chunk> bySource = new HashMap<>();
                chunks.forEach(c -> bySource.put(c.source(), c));
                List<Hit> out = new ArrayList<>();
                for (Store.VecHit h : store.pgSearch("[" + format(qv) + "]", activeEmbedder, type, k)) {
                    Chunk c = bySource.get(h.source());
                    if (c != null && h.score() >= 0.12) out.add(new Hit(c, h.score()));
                }
                return out;
            } catch (Exception e) {
                log.warn("pgvector search failed ({}), using in-memory search", e.getMessage());
                pgOn = false;
            }
        }
        return chunks.stream()
                .filter(c -> type == null || c.type().equals(type))
                .map(c -> new Hit(c, Embedder.cosine(qv, c.vector())))
                .filter(h -> h.score() >= 0.12)
                .sorted(Comparator.comparingDouble(Hit::score).reversed())
                .limit(k).toList();
    }

    /** Exact lookup of a client profile by the sender's email domain. */
    public Optional<Chunk> clientByEmail(String email) {
        if (email == null || !email.contains("@")) return Optional.empty();
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        return chunks.stream().filter(c -> c.type().equals("client") && domain.equals(c.fields().get("domain"))).findFirst();
    }

    public Optional<Chunk> process(String department) {
        String key = department.toLowerCase(Locale.ROOT);
        return chunks.stream().filter(c -> c.type().equals("process") && c.source().contains("escalation-" + key)).findFirst();
    }

    public int size() { return chunks.size(); }

    public String embedderId() { return activeEmbedder; }

    static Map<String, String> fields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.lines().skip(1).toList()) {
            int i = line.indexOf(':');
            if (i > 0 && i < 20 && !line.startsWith(" ")) out.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
        }
        return out;
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String format(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.toString();
    }

    private static float[] parse(String s) {
        String[] p = s.split(",");
        float[] v = new float[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Float.parseFloat(p[i]);
        return v;
    }
}
