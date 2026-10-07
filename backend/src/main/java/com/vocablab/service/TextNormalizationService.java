package com.vocablab.service;

public interface TextNormalizationService {
    NormalizedText normalizeForReview(String value);
    String normalizeLemma(String value);
    String normalizedHeader(String value);

    record NormalizedText(String text, boolean repairProposed, String repairedSuggestion, double confidence) {}
}
