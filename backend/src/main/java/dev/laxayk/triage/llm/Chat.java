package dev.laxayk.triage.llm;

/** The slice of the LLM client the agents need. Lets tests plug in a scripted model. */
public interface Chat {
    boolean enabled();

    String model();

    String chat(String system, String user, boolean json) throws LlmClient.LlmException;
}
