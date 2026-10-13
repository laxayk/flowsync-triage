package dev.laxayk.triage;

import dev.laxayk.triage.ingest.SampleLoader;
import dev.laxayk.triage.pipeline.Pipeline;
import dev.laxayk.triage.store.Store;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regen;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class RegenerateTest {
    @Autowired SampleLoader samples;
    @Autowired Pipeline pipeline;
    @Autowired Store store;

    @Test void regenerateSupersedesTheOpenDraftAndWritesANewOne() {
        samples.load();
        assertTrue(pipeline.await(15000));
        long tid = store.recentEmails(100).stream().filter(e -> e.messageId().equals("<01-query-replay@samples.flowsync>")).findFirst().orElseThrow().ticketId();
        var before = store.draftsForTicket(tid);
        assertEquals(1, before.size());
        assertTrue(pipeline.regenerate(tid, "tester"));
        var after = store.draftsForTicket(tid);
        assertEquals(2, after.size());
        assertEquals(1, after.stream().filter(d -> d.status().equals("DRAFT")).count(), "exactly one open draft remains");
        assertEquals("REJECTED", store.draft(before.get(0).id()).orElseThrow().status());
        assertFalse(pipeline.regenerate(999999, "tester"));
    }
}
