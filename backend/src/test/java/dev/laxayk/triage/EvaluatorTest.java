package dev.laxayk.triage;

import dev.laxayk.triage.pipeline.Evaluator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class EvaluatorTest {
    @Autowired Evaluator evaluator;

    /** Regression guard for the keyword fallback on the bundled labelled samples. */
    @Test
    void fallbackStaysAccurateOnLabelledSamples() throws Exception {
        Map<String, Object> r = evaluator.run();
        assertEquals(16, r.get("samples"));
        assertTrue((double) r.get("dispositionAccuracy") >= 0.95, r.toString());
        assertTrue((double) r.get("categoryAccuracy") >= 0.9, r.toString());
    }

    @Test
    void messySetReportsHonestly() throws Exception {
        Map<String, Object> r = evaluator.run("messy");
        System.out.println("MESSY " + r.get("samples") + " disp=" + r.get("dispositionAccuracy") + " cat=" + r.get("categoryAccuracy"));
        ((java.util.List<?>) r.get("rows")).forEach(x -> { if (!(boolean) ((Map<?, ?>) x).get("ok")) System.out.println("MISS " + x); });
        assertEquals(28, r.get("samples"));
        // The all-caps outage mail must reach a human, not the spam folder.
        assertTrue(((java.util.List<?>) r.get("rows")).stream().anyMatch(x -> "m02-all-caps".equals(((Map<?, ?>) x).get("file")) && "TICKET".equals(((Map<?, ?>) x).get("gotDisposition"))));
        assertTrue((double) r.get("dispositionAccuracy") >= 0.85, r.toString());
    }
}
