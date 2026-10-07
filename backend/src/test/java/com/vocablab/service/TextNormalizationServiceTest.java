package com.vocablab.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vocablab.service.impl.TextNormalizationServiceImpl;
import org.junit.jupiter.api.Test;

class TextNormalizationServiceTest {
    private final TextNormalizationService normalizer = new TextNormalizationServiceImpl();

    @Test
    void normalizesCanonicalUnicodeWithoutChangingVietnameseText() {
        var result = normalizer.normalizeForReview("  ba\u0323n  ");
        assertEquals("bạn", result.text());
        assertFalse(result.repairProposed());
    }

    @Test
    void proposesHighConfidenceRepairForCommonVietnameseMojibake() {
        var result = normalizer.normalizeForReview("báº¡n");
        assertEquals("báº¡n", result.text());
        assertTrue(result.repairProposed());
        assertEquals("bạn", result.repairedSuggestion());
    }

    @Test
    void foldsWhitespaceAndCaseForLemmaKeys() {
        assertEquals("ice cream", normalizer.normalizeLemma("  ICE\u00a0  cream "));
    }
}
