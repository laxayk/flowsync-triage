package dev.laxayk.triage.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacingTest {
    @Test void callsAreSpacedByTheConfiguredInterval() throws Exception {
        LlmClient c = new LlmClient(new ObjectMapper(), "groq", "", "", "", 5, 1);
        c.pace(); // no spacing configured when built by hand
        c.setMinIntervalMs(200);
        long t0 = System.nanoTime();
        c.pace(); c.pace(); c.pace();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 390, "three calls need two gaps, took " + ms + " ms");
    }
}
