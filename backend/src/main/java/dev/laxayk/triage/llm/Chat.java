package dev.laxayk.triage.llm;

/** The slice of the LLM client the agents need. Lets tests plug in a scripted model. */
public interface Chat {
    boolean enabled();

    String model();

    String chat(String system, String user, boolean json) throws LlmClient.LlmException;

    /** True when the provider is spoken to with real function calling (tools declared in the request). */
    default boolean nativeTools() { return false; }

    /**
     * One agent turn. With native tools the model's function call comes back already converted to this app's action message,
     * {"action":"tool","tool":...,"args":{...}} or {"action":"final","reply":...}; the default is the plain JSON-in-text protocol.
     */
    default String agentTurn(String system, String user, java.util.List<java.util.Map<String, Object>> toolSpecs) throws LlmClient.LlmException {
        return chat(system, user, true);
    }
}
