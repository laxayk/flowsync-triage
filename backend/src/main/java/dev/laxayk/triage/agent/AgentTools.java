package dev.laxayk.triage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.laxayk.triage.store.Store;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The tools the drafting agent may call. All of them are read-only: they look things up and return text.
 * Nothing here sends, edits or deletes, so even a model that has been talked into something by an email cannot cause a side effect.
 * The customer is always identified from the stored sender address, never from model-supplied arguments.
 */
@Component
public class AgentTools {
    public record Context(String senderEmail, long ticketId) {}

    public static final Map<String, String> CATALOG = new LinkedHashMap<>();
    static {
        CATALOG.put("search_knowledge", "args: {\"query\": string, \"scope\": \"docs\" | \"processes\" | \"all\"}. Semantic search over product docs and internal process notes.");
        CATALOG.put("lookup_customer", "args: {}. Profile of the sender's company: plan, SLA, connectors, success manager, notes.");
        CATALOG.put("ticket_history", "args: {}. The sender's previous tickets (key, subject, category, status).");
        CATALOG.put("open_incidents", "args: {}. Open outage tickets right now, to check whether this is a known incident.");
    }

    private final KnowledgeBase kb;
    private final Store store;

    public AgentTools(KnowledgeBase kb, Store store) {
        this.kb = kb;
        this.store = store;
    }

    /** The same tools as native function definitions (OpenAI-compatible "tools" array), plus final_reply to finish. */
    public static java.util.List<Map<String, Object>> toolSpecs() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(fn("search_knowledge", CATALOG.get("search_knowledge").replaceFirst("^args:[^.]*\\.\\s*", ""),
                Map.of("query", Map.of("type", "string"), "scope", Map.of("type", "string", "enum", java.util.List.of("docs", "processes", "all"))),
                java.util.List.of("query")));
        for (String t : java.util.List.of("lookup_customer", "ticket_history", "open_incidents")) {
            out.add(fn(t, CATALOG.get(t).replaceFirst("^args:[^.]*\\.\\s*", ""), Map.of(), java.util.List.of()));
        }
        out.add(fn("final_reply", "Finish: the reply email to send to the customer (a human reviews it first).",
                Map.of("reply", Map.of("type", "string", "description", "plain-text email body, friendly, under 150 words, signed FlowSync Support"),
                        "sources", Map.of("type", "array", "items", Map.of("type", "string")),
                        "needs_human", Map.of("type", "boolean")),
                java.util.List.of("reply", "needs_human")));
        return out;
    }

    private static Map<String, Object> fn(String name, String description, Map<String, Object> props, java.util.List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", required);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name); f.put("description", description); f.put("parameters", params);
        return Map.of("type", "function", "function", f);
    }

    public static String catalogText() {
        return CATALOG.entrySet().stream().map(e -> "- " + e.getKey() + " " + e.getValue()).collect(Collectors.joining("\n"));
    }

    /** Returns the tool output (trimmed), or an error string the model can read. Never throws. */
    public String run(String tool, JsonNode args, Context ctx) {
        try {
            String out = switch (tool == null ? "" : tool) {
                case "search_knowledge" -> search(args);
                case "lookup_customer" -> customer(ctx);
                case "ticket_history" -> history(ctx);
                case "open_incidents" -> incidents();
                default -> "ERROR: unknown tool '" + tool + "'. Available: " + String.join(", ", CATALOG.keySet());
            };
            return out.length() > 1400 ? out.substring(0, 1400) + "\n[truncated]" : out;
        } catch (RuntimeException e) {
            return "ERROR: tool failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    private String search(JsonNode args) {
        String q = args == null ? "" : args.path("query").asText("");
        if (q.isBlank()) return "ERROR: query is required";
        String scope = args.path("scope").asText("all");
        String type = switch (scope) { case "docs" -> "doc"; case "processes" -> "process"; default -> null; };
        // Client profiles are only reachable through lookup_customer, which is bound to the real sender.
        List<KnowledgeBase.Hit> hits = kb.search(q, type, 6).stream().filter(h -> !h.chunk().type().equals("client")).limit(3).toList();
        if (hits.isEmpty()) return "No matching documents.";
        return hits.stream().map(h -> "[" + h.chunk().title() + " | " + h.chunk().type() + " | score " + String.format(Locale.ROOT, "%.2f", h.score())
                + "]\n" + body(h.chunk().text())).collect(Collectors.joining("\n---\n"));
    }

    private static String body(String text) {
        return text.lines().skip(1).filter(l -> !l.isBlank()).collect(Collectors.joining("\n"));
    }

    private String customer(Context ctx) {
        return kb.clientByEmail(ctx.senderEmail())
                .map(c -> "Customer: " + c.title() + "\n" + body(c.text()))
                .orElse("No customer profile for " + ctx.senderEmail() + " (unknown company).");
    }

    private String history(Context ctx) {
        var rows = store.ticketHistory(ctx.senderEmail(), ctx.ticketId());
        if (rows.isEmpty()) return "No previous tickets from this sender.";
        return rows.stream().map(r -> r.key() + " " + r.category() + " " + r.status() + ": " + r.subject()).collect(Collectors.joining("\n"));
    }

    private String incidents() {
        var rows = store.openIncidents();
        if (rows.isEmpty()) return "No open incidents.";
        return rows.size() + " open incident ticket(s):\n" + rows.stream().map(r -> r.key() + " " + r.subject()).collect(Collectors.joining("\n"));
    }
}
