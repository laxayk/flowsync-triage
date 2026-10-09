package dev.laxayk.triage.domain;

public record Classification(Category category, String priority, String summary, double confidence,
                             String source, boolean needsReview, String reason) {}
