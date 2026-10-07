package com.vocablab.dto.response;

import java.util.List;
import java.util.UUID;

public record EnrichmentSuggestionResponse(
        UUID jobId, String provider, String model, boolean ambiguous, List<Suggestion> suggestions) {
    public record Suggestion(String partOfSpeech, String definitionEn, String explanationVi,
            String cefrLevel, String topic, List<String> examples, List<String> relatedWords) {}
}
