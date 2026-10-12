package dev.laxayk.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.laxayk.triage.pipeline.Pipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ingest.samples-on-start=true", "app.llm.provider=none"})
@AutoConfigureMockMvc
class ApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Pipeline pipeline;

    private String login(String user, String pw) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private JsonNode fetch(String url, String token) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void authRolesAndApprovalFlow() throws Exception {
        assertTrue(pipeline.await(15000));
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"agent\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());

        String agent = login("agent", "agent123");
        String approver = login("approver", "approver123");
        JsonNode tickets = fetch("/api/tickets", agent);
        assertTrue(tickets.size() >= 7);

        // find a ticket with a draft
        long ticketId = -1; long draftId = -1;
        for (JsonNode t : tickets) {
            JsonNode d = fetch("/api/tickets/" + t.get("id").asLong(), agent).get("drafts");
            if (d.size() > 0) { ticketId = t.get("id").asLong(); draftId = d.get(0).get("id").asLong(); break; }
        }
        assertTrue(draftId > 0, "some ticket should have a draft");

        // an AGENT cannot send
        mvc.perform(post("/api/drafts/" + draftId + "/approve").header("Authorization", "Bearer " + agent))
                .andExpect(status().isForbidden());
        // an APPROVER can, once
        mvc.perform(post("/api/drafts/" + draftId + "/approve").header("Authorization", "Bearer " + approver))
                .andExpect(status().isOk());
        mvc.perform(post("/api/drafts/" + draftId + "/approve").header("Authorization", "Bearer " + approver))
                .andExpect(status().isConflict());
        JsonNode detail = fetch("/api/tickets/" + ticketId, agent);
        assertEquals("SENT", detail.get("drafts").get(0).get("status").asText());
        assertTrue(detail.get("audit").toString().contains("APPROVE_SEND"));

        // security hold can only be released by an approver, and the release creates a review-flagged ticket
        JsonNode held = fetch("/api/emails?disposition=SECURITY_HOLD", agent);
        assertEquals(3, held.size());
        long heldId = held.get(0).get("id").asLong();
        mvc.perform(post("/api/emails/" + heldId + "/release").header("Authorization", "Bearer " + agent))
                .andExpect(status().isForbidden());

        // corrections are audited and validated
        long id = tickets.get(0).get("id").asLong();
        mvc.perform(patch("/api/tickets/" + id).header("Authorization", "Bearer " + agent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":\"P9\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/tickets/" + id).header("Authorization", "Bearer " + agent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":\"P2\",\"assignee\":\"sam\"}")).andExpect(status().isOk());
        assertTrue(fetch("/api/tickets/" + id, agent).get("audit").toString().contains("UPDATE_TICKET"));

        assertTrue(fetch("/api/stats", agent).has("latencyPercentilesMs"));
    }
}
