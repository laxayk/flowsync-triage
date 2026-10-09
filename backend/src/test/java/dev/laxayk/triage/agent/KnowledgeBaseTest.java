package dev.laxayk.triage.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none"})
class KnowledgeBaseTest {
    @Autowired KnowledgeBase kb;

    @Test void indexesDocsClientsAndProcesses() {
        assertTrue(kb.size() >= 17);
        assertEquals("hash-2048", kb.embedderId());
    }

    @Test void searchFindsTheRightDocument() {
        assertTrue(kb.search("how do I enable SAML SSO for my team", "doc", 2).get(0).chunk().title().toLowerCase().contains("sso"));
        assertTrue(kb.search("charged twice, need a refund for the duplicate invoice", "doc", 1).get(0).chunk().title().toLowerCase().contains("billing"));
        assertTrue(kb.search("replay only the failed rows after a timeout", "doc", 1).get(0).chunk().title().toLowerCase().contains("replay"));
    }

    @Test void searchRespectsTypeAndIgnoresNoise() {
        assertTrue(kb.search("what is the escalation process for an outage", "process", 1).get(0).chunk().source().contains("sre"));
        assertTrue(kb.search("zzzz qqqq xxxx", null, 3).isEmpty());
    }

    @Test void clientLookupIsByExactSenderDomain() {
        assertEquals("Pro", kb.clientByEmail("dana@northwind-retail.example").orElseThrow().fields().get("plan"));
        assertTrue(kb.clientByEmail("someone@unknown.example").isEmpty());
        assertTrue(kb.clientByEmail("not-an-email").isEmpty());
    }
}
