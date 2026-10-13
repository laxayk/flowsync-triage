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

    /** One searchable piece of a file before embedding. Product docs are split by "## " section, everything else stays whole. */
    record Raw(String source, String type, String title, String text, Map<String, String> fields) {}

    static List<Raw> split(String rel, String type, String text) {
        String docTitle = text.lines().findFirst().orElse(rel).replaceFirst("^#\\s*", "");
        if (!type.equals("doc") || !text.contains("\n## ")) return List.of(new Raw(rel, type, docTitle, text, fields(text)));
        List<Raw> out = new ArrayList<>();
        String[] parts = text.split("(?m)^## ");
        Map<String, String> meta = fields(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String heading = parts[i].lines().findFirst().orElse("").trim();
            String body = parts[i].lines().skip(1).filter(l -> !l.isBlank()).reduce((x, y) -> x + "\n" + y).orElse("");
            String title = docTitle + " > " + heading;
            out.add(new Raw(rel + "#" + i, type, title, title + "\n" + body, meta));
        }
        return out;
    }

    public synchronized void reindex() {
        Embedder active = embedder;
        try {
            Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath:/kb/**/*.md");
            Arrays.sort(all, Comparator.comparing(r -> String.valueOf(r.getDescription())));
            List<Raw> raws = new ArrayList<>();
            for (Resource r : all) {
                String path = r.getURL().toString();
                String rel = path.substring(path.indexOf("/kb/") + 4);
                String type = rel.startsWith("clients/") ? "client" : rel.startsWith("processes/") ? "process" : "doc";
                raws.addAll(split(rel, type, new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()));
            }
            List<Chunk> fresh = embedAll(raws, active);
            if (fresh == null) { active = new Embedder.Hashing(); fresh = embedAll(raws, active); }
            chunks.clear();
            chunks.addAll(fresh);
            activeEmbedder = active.id();
            lexical = new Lexical(fresh);
            syncPg(fresh);
            log.info("Knowledge base ready: {} chunks with {}", fresh.size(), active.id());
        } catch (Exception e) {
            log.error("Knowledge base indexing failed", e);
        }
    }

    /** Embeds every piece (reusing stored vectors when text and embedder are unchanged). Null when the embedder failed. */
    private List<Chunk> embedAll(List<Raw> raws, Embedder active) {
        List<Chunk> fresh = new ArrayList<>();
        for (Raw r : raws) {
            String sha = sha(r.text());
            float[] vec = null;
            var cached = store.kbChunk(r.source());
            if (cached.isPresent() && cached.get().sha().equals(sha) && cached.get().embedder().equals(active.id())) vec = parse(cached.get().vector());
            if (vec == null) {
                try {
                    vec = active.embed(r.title() + "\n" + r.text());
                } catch (Exception e) {
                    if (active instanceof Embedder.Hashing) throw new IllegalStateException(e);
                    log.warn("Embedder {} failed ({}), switching to local hashing for the whole index", active.id(), e.getMessage());
                    return null;
                }
                store.upsertKbChunk(r.source(), r.type(), r.title(), sha, active.id(), format(vec));
            }
            fresh.add(new Chunk(r.source(), r.type(), r.title(), r.text(), r.fields(), vec));
        }
        return fresh;
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

    /** Minimum relevance (0..1, share of the question's weighted terms found in the chunk) for a chunk to count as an answer. */
    public static final double MIN_RELEVANCE = 0.30;

    private volatile Lexical lexical = new Lexical(List.of());

    /**
     * Hybrid search: BM25 keyword ranking and vector similarity are merged with reciprocal rank fusion. A hit's score is the
     * weighted share of the question's terms that the chunk contains, which is what the minimum-relevance bar is applied to.
     * type is "doc", "client", "process" or null for everything.
     */
    public List<Hit> search(String query, String type, int k) {
        return search(query, type, k, MIN_RELEVANCE);
    }

    public List<Hit> search(String query, String type, int k, double minRelevance) {
        float[] q;
        try {
            q = (activeEmbedder != null && activeEmbedder.startsWith("http-") ? embedder : new Embedder.Hashing()).embed(query);
        } catch (Exception e) {
            q = new Embedder.Hashing().embed(query);
        }
        final float[] qv = q;
        List<Chunk> pool = chunks.stream().filter(c -> type == null || c.type().equals(type)).toList();
        Lexical lx = lexical;
        Map<Chunk, Double> bm = new HashMap<>(), cov = new HashMap<>(), cos = new HashMap<>();
        for (Chunk c : pool) {
            double[] l = lx.score(query, c);
            bm.put(c, l[0]);
            cov.put(c, l[1]);
            cos.put(c, (double) Embedder.cosine(qv, c.vector()));
        }
        Map<Chunk, Double> fused = new HashMap<>();
        rank(pool, bm, fused);
        rank(pool, cos, fused);
        return pool.stream()
                .filter(c -> cov.get(c) >= minRelevance)
                .sorted(Comparator.comparingDouble((Chunk c) -> fused.get(c)).reversed())
                .limit(k).map(c -> new Hit(c, cov.get(c))).toList();
    }

    private static void rank(List<Chunk> pool, Map<Chunk, Double> scores, Map<Chunk, Double> fused) {
        List<Chunk> order = pool.stream().filter(c -> scores.get(c) > 0).sorted(Comparator.comparingDouble((Chunk c) -> scores.get(c)).reversed()).toList();
        for (int i = 0; i < order.size(); i++) fused.merge(order.get(i), 1.0 / (60 + i + 1), Double::sum);
        for (Chunk c : pool) fused.putIfAbsent(c, 0.0);
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
