package dev.laxayk.triage.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/** H2 has no pgvector. Asking for it must degrade to in-memory search, not break the knowledge base. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kbpg;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=false", "app.llm.provider=none", "app.embed.pgvector=true"})
class PgVectorFallbackTest {
    @Autowired KnowledgeBase kb;

    @Test void fallsBackWhenDatabaseHasNoPgvector() {
        assertFalse(kb.pgvectorActive());
        assertTrue(kb.search("how do I enable SAML SSO", "doc", 1).get(0).chunk().title().toLowerCase().contains("sso"));
    }
}
