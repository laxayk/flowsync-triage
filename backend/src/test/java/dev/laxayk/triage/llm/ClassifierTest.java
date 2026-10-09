package dev.laxayk.triage.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.Classification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierTest {
    private final LlmClient off = new LlmClient(new ObjectMapper(), "groq", "", "", "", 5, 1);
    private final Classifier c = new Classifier(off, new HeuristicClassifier(), new ObjectMapper());

    @Test void parsesValidJsonEvenWithChatter() {
        Classification r = c.parse("Sure!\n{\"category\":\"bug\",\"priority\":\"p2\",\"summary\":\"dupes\",\"confidence\":0.9}");
        assertEquals(Category.BUG, r.category());
        assertEquals("P2", r.priority());
        assertFalse(r.needsReview());
    }

    @Test void rejectsUnknownCategoryAndClampsFields() {
        assertThrows(IllegalArgumentException.class, () -> c.parse("{\"category\":\"DELETE_EVERYTHING\"}"));
        Classification r = c.parse("{\"category\":\"QUERY\",\"priority\":\"P9\",\"confidence\":7}");
        assertEquals("P3", r.priority());
        assertEquals(1.0, r.confidence());
    }

    @Test void lowConfidenceNeedsReview() {
        assertTrue(c.parse("{\"category\":\"QUERY\",\"priority\":\"P4\",\"confidence\":0.3}").needsReview());
    }

    @Test void missingApiKeyDisablesLlmAndFallsBack() {
        assertFalse(off.enabled());
    }

    @Test void promptWrapsEmailAsData() {
        var e = new dev.laxayk.triage.domain.ParsedEmail("<1>", null, null, "a@b.example", "A", "s@x", "Subj", "body text", java.time.Instant.now(), false, java.util.List.of());
        String p = Classifier.userPrompt(e);
        assertTrue(p.startsWith("<email>") && p.endsWith("</email>"));
    }
}
