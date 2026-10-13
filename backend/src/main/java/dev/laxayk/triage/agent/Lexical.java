package dev.laxayk.triage.agent;

import java.util.*;
import java.util.regex.Pattern;

/** Keyword side of the hybrid search: BM25 ranking plus an IDF-weighted share of the question's terms found in a chunk. */
final class Lexical {
    private static final Set<String> STOP = Set.of("a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were",
            "be", "can", "could", "do", "does", "did", "we", "you", "your", "our", "it", "this", "that", "i", "me", "my", "have", "has", "had",
            "how", "what", "which", "when", "where", "why", "please", "hi", "hello", "hey", "thanks", "thank", "regards", "team", "support",
            "from", "at", "as", "by", "any", "there", "not", "no", "yes", "if", "will", "would", "about", "get", "got", "want", "need", "know",
            "also", "just", "like", "tell", "us", "should", "than", "then", "them", "they", "its", "into", "out", "up", "so", "but", "all",
            "flowsync", "dear", "question", "quick", "using", "use", "much", "many", "more", "some", "been", "am", "im", "ive");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final double K1 = 1.2, B = 0.75;

    private final Map<KnowledgeBase.Chunk, List<String>> docs = new HashMap<>();
    private final Map<String, Integer> df = new HashMap<>();
    private final double avgLen;
    private final int n;

    Lexical(List<KnowledgeBase.Chunk> chunks) {
        long total = 0;
        for (KnowledgeBase.Chunk c : chunks) {
            List<String> t = tokens(c.title() + " " + c.text() + " " + c.fields().getOrDefault("tags", ""));
            docs.put(c, t);
            total += t.size();
            for (String w : new HashSet<>(t)) df.merge(w, 1, Integer::sum);
        }
        n = chunks.size();
        avgLen = n == 0 ? 1 : (double) total / n;
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 2 || STOP.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private double idf(String w) {
        int d = df.getOrDefault(w, 0);
        return Math.log(1 + (n - d + 0.5) / (d + 0.5));
    }

    /** Returns {bm25, coverage}. Coverage only counts query terms the corpus knows; unknown terms cannot make a chunk look relevant. */
    double[] score(String query, KnowledgeBase.Chunk c) {
        List<String> doc = docs.getOrDefault(c, List.of());
        Map<String, Integer> tf = new HashMap<>();
        for (String w : doc) tf.merge(w, 1, Integer::sum);
        double bm = 0, matched = 0, all = 0;
        for (String w : new LinkedHashSet<>(tokens(query))) {
            double idf = idf(w);
            all += idf;
            int f = tf.getOrDefault(w, 0);
            if (f == 0) continue;
            matched += idf;
            bm += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * doc.size() / avgLen));
        }
        return new double[]{bm, all == 0 ? 0 : matched / all};
    }
}
