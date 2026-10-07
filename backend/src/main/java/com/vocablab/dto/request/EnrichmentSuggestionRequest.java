package com.vocablab.dto.request;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record EnrichmentSuggestionRequest(@NotNull UUID vocabularyId, UUID vocabularySenseId, String partOfSpeech) {}
