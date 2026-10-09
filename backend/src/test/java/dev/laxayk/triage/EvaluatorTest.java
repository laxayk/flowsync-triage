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
}
