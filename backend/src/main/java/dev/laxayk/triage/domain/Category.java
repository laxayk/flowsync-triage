package dev.laxayk.triage.domain;

/** What an email is about. Non-ticket categories are never turned into engineering tickets. */
public enum Category {
    QUERY(true), BUG(true), WRONG_BEHAVIOUR(true), ENHANCEMENT(true),
    BILLING(true), ACCOUNT_ACCESS(true), OUTAGE(true),
    PRAISE(false), SPAM(false), SALES(false), IRRELEVANT(false);

    private final boolean ticketable;

    Category(boolean ticketable) { this.ticketable = ticketable; }

    public boolean ticketable() { return ticketable; }

    public static Category parse(String s, Category fallback) {
        if (s == null) return fallback;
        try {
            return valueOf(s.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
