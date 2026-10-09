package dev.laxayk.triage.agent;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns text into a unit-length vector. Implementations must be deterministic for the same id(). */
public interface Embedder {
    String id();

    float[] embed(String text) throws Exception;

    /**
     * Local feature-hashing embedder: no model, no network, no memory cost. Unigrams and bigrams are hashed into a
     * fixed number of buckets with a sign bit, then L2-normalised. It captures word overlap rather than meaning, which is
     * enough for a small, well-worded knowledge base and keeps the app usable offline on an 8 GB laptop. A model-based
     * embedder (Ollama or OpenAI) can replace it through configuration.
     */
    final class Hashing implements Embedder {
        static final int DIM = 2048;
        private static final Pattern WORD = Pattern.compile("[a-z0-9]{2,}");
        private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "have", "are",
                "you", "your", "can", "how", "our", "but", "not", "was", "has", "get", "does", "will", "any", "all", "its", "into");

        @Override
        public String id() { return "hash-" + DIM; }

        @Override
        public float[] embed(String text) {
            float[] v = new float[DIM];
            List<String> toks = new ArrayList<>();
            Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
            while (m.find()) if (!STOP.contains(m.group())) toks.add(stem(m.group()));
            for (int i = 0; i < toks.size(); i++) {
                add(v, toks.get(i), 1f);
                if (i + 1 < toks.size()) add(v, toks.get(i) + " " + toks.get(i + 1), 0.5f);
            }
            return normalize(v);
        }

        private static String stem(String w) {
            for (String suf : new String[]{"ing", "ed", "es", "s"})
                if (w.length() > suf.length() + 3 && w.endsWith(suf)) { w = w.substring(0, w.length() - suf.length()); break; }
            return w.length() > 4 && w.endsWith("e") ? w.substring(0, w.length() - 1) : w;
        }

        private static void add(float[] v, String tok, float weight) {
            long h = 0xcbf29ce484222325L; // FNV-1a, 64 bit
            for (int i = 0; i < tok.length(); i++) { h ^= tok.charAt(i); h *= 0x100000001b3L; }
            h ^= (h >>> 33); h *= 0xff51afd7ed558ccdL; h ^= (h >>> 33); // final mix so similar strings spread out
            int idx = (int) Long.remainderUnsigned(h >>> 1, DIM);
            v[idx] += ((h & 1L) == 0 ? 1f : -1f) * weight;
        }
    }

    static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;
    }
}
