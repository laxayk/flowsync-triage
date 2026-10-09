package dev.laxayk.triage.pipeline;

import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.Classification;
import dev.laxayk.triage.domain.ParsedEmail;
import dev.laxayk.triage.gate.GateResult;
import dev.laxayk.triage.gate.SafetyGate;
import dev.laxayk.triage.ingest.EmailParser;
import dev.laxayk.triage.llm.Classifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Runs the labelled sample mailbox through gate + classifier without touching the database and reports accuracy.
 * With an LLM configured this measures the real model; without one it measures the keyword fallback.
 * The sample set is small and written by the same author as the rules, so treat the numbers as a regression check,
 * not as a claim about production accuracy.
 */
@Service
public class Evaluator {
    private final SafetyGate gate;
    private final Classifier classifier;

    public Evaluator(SafetyGate gate, Classifier classifier) {
        this.gate = gate;
        this.classifier = classifier;
    }

    public Map<String, Object> run() throws Exception { return run("basic"); }

    /** set is "basic" (clean samples) or "messy" (forwarded chains, other languages, one-liners, injection in footers). */
    public Map<String, Object> run(String set) throws Exception {
        String dir = "messy".equals(set) ? "samples/eval/" : "samples/";
        List<Map<String, Object>> rows = new ArrayList<>();
        int dispOk = 0, catTotal = 0, catOk = 0, total = 0;
        long start = System.currentTimeMillis();
        try (var r = new BufferedReader(new InputStreamReader(new ClassPathResource(dir + "labels.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);
                ParsedEmail e;
                try (var in = new ClassPathResource(dir + p[0] + ".eml").getInputStream()) { e = EmailParser.parse(in); }
                GateResult g = gate.evaluate(e);
                String disp = g.verdict().disposition();
                String cat = g.verdict() == dev.laxayk.triage.domain.Verdict.CLEAN ? null : switch (g.verdict()) {
                    case SPAM -> "SPAM"; case SALES -> "SALES"; case AUTO_REPLY -> "IRRELEVANT"; default -> null; };
                String via = "gate";
                if (g.verdict() == dev.laxayk.triage.domain.Verdict.CLEAN) {
                    Classification c = classifier.classify(e);
                    cat = c.category().name();
                    disp = c.category().ticketable() ? "TICKET" : "NO_TICKET";
                    via = c.source();
                }
                total++;
                boolean dOk = disp.equals(p[1]);
                if (dOk) dispOk++;
                boolean cOk = true;
                if (p.length > 2 && !p[2].isBlank()) { catTotal++; cOk = p[2].equals(cat); if (cOk) catOk++; }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", p[0]); row.put("expectedDisposition", p[1]); row.put("gotDisposition", disp);
                row.put("expectedCategory", p.length > 2 ? p[2] : ""); row.put("gotCategory", cat);
                row.put("via", via); row.put("ok", dOk && cOk);
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set", set == null ? "basic" : set);
        out.put("samples", total);
        out.put("dispositionAccuracy", total == 0 ? 0 : Math.round(1000.0 * dispOk / total) / 1000.0);
        out.put("categoryAccuracy", catTotal == 0 ? 0 : Math.round(1000.0 * catOk / catTotal) / 1000.0);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("note", "Small author-written set: a regression check, not a production accuracy claim.");
        out.put("rows", rows);
        return out;
    }
}
