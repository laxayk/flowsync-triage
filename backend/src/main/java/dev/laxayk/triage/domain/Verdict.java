package dev.laxayk.triage.domain;

/** Result of the deterministic safety/relevance gate that runs before any LLM call. */
public enum Verdict {
    CLEAN, AUTO_REPLY, SALES, SPAM, PHISHING, PROMPT_INJECTION, SUSPICIOUS_ATTACHMENT;

    /** Where the mail goes when the gate does not return CLEAN. */
    public String disposition() {
        return switch (this) {
            case CLEAN -> "TICKET";
            case AUTO_REPLY, SALES -> "NO_TICKET";
            case SPAM -> "QUARANTINED";
            case PHISHING, PROMPT_INJECTION, SUSPICIOUS_ATTACHMENT -> "SECURITY_HOLD";
        };
    }
}
