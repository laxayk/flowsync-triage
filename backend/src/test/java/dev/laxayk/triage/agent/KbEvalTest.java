package dev.laxayk.triage.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression check for retrieval: author-written labelled questions (kb-eval.tsv), each marked with the doc that should answer
 * it or NONE. Compares the old way (one whole-file vector per doc, cosine bar 0.12) with the hybrid search and prints both.
 * The questions were written by the same author as the docs, so this guards against regressions and is not a benchmark.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kbeval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class KbEvalTest {
    @Autowired KnowledgeBase kb;

    record Q(String text, String expect) {}

    static List<Q> load() throws Exception {
        List<Q> out = new ArrayList<>();
        try (var in = KbEvalTest.class.getResourceAsStream("/kb-eval.tsv")) {
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] p = l.split("\t");
                if (p.length == 2) out.add(new Q(p[0], p[1]));
            }
        }
        return out;
    }

    @Test void hybridSearchBeatsWholeFileVectorsAndHandsOffUnanswerable() throws Exception {
        List<Q> qs = load();
        // old: whole docs, hash vectors, cosine >= 0.12
        Embedder h = new Embedder.Hashing();
        Map<String, float[]> whole = new LinkedHashMap<>();
        for (var r : new PathMatchingResourcePatternResolver().getResources("classpath:/kb/docs/*.md")) {
            String t = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            whole.put(r.getFilename().replace(".md", ""), h.embed(t.lines().findFirst().orElse("") + "\n" + t));
        }
        int answerable = 0, oldTop1 = 0, oldTop3 = 0, newTop1 = 0, newTop3 = 0, none = 0, oldNoneOk = 0, newNoneOk = 0;
        StringBuilder misses = new StringBuilder();
        for (Q q : qs) {
            float[] qv = h.embed(q.text());
            List<String> oldOrder = whole.entrySet().stream().filter(e -> Embedder.cosine(qv, e.getValue()) >= 0.12)
                    .sorted((a, b) -> Double.compare(Embedder.cosine(qv, b.getValue()), Embedder.cosine(qv, a.getValue()))).map(Map.Entry::getKey).limit(3).toList();
            List<String> newOrder = kb.search(q.text(), "doc", 3).stream().map(x -> x.chunk().source().replaceAll("^docs/|\\.md.*$", "")).toList();
            if (q.expect().equals("NONE")) {
                none++;
                if (oldOrder.isEmpty()) oldNoneOk++;
                if (newOrder.isEmpty()) newNoneOk++; else misses.append("  unanswerable but answered: ").append(q.text()).append(" -> ").append(newOrder).append('\n');
            } else {
                answerable++;
                if (!oldOrder.isEmpty() && oldOrder.get(0).equals(q.expect())) oldTop1++;
                if (oldOrder.contains(q.expect())) oldTop3++;
                if (!newOrder.isEmpty() && newOrder.get(0).equals(q.expect())) newTop1++;
                if (newOrder.contains(q.expect())) newTop3++; else misses.append("  missed: ").append(q.text()).append(" -> ").append(newOrder).append('\n');
            }
        }
        System.out.printf("KB EVAL (%d answerable, %d unanswerable)%n  old whole-file vectors: hit@1 %d, hit@3 %d, unanswerable handed off %d/%d%n  hybrid sections:        hit@1 %d, hit@3 %d, unanswerable handed off %d/%d%n%s",
                answerable, none, oldTop1, oldTop3, oldNoneOk, none, newTop1, newTop3, newNoneOk, none, misses);
        assertTrue(newTop3 >= oldTop3, "hybrid search must not be worse than whole-file vectors at hit@3");
        assertTrue(newNoneOk >= oldNoneOk, "hybrid search must hand off at least as many unanswerable questions");
    }

    @Test void theReportedServicesQuestionNoLongerGetsUnrelatedDocs() {
        var hits = kb.search("Does FlowSync sync in real time and support Google Sheets?", "doc", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).chunk().source().startsWith("docs/02-") || hits.get(0).chunk().source().startsWith("docs/11-"));
    }
}
