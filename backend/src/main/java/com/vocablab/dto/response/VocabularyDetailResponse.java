package com.vocablab.dto.response;

import java.util.List;
import java.util.UUID;

public record VocabularyDetailResponse(
        UUID id,
        String language,
        String lemma,
        List<Sense> senses,
        List<Pronunciation> pronunciations) {
    public record Sense(
            UUID id,
            String partOfSpeech,
            int order,
            String definitionEn,
            String explanationVi,
            String cefrLevel,
            String topic,
            String register,
            List<Example> examples) {}
    public record Example(UUID id, String sentenceEn, String translationVi, int order) {}
    public record Pronunciation(
            UUID id, UUID vocabularySenseId, String accent, String ipa,
            String stressPattern, boolean audioAvailable, String audioPath, String sourceType) {}
}
