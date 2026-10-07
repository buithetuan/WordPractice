package com.vocablab.dto.response;

import java.time.Instant;
import java.util.UUID;

public record VocabularySetItemResponse(
        UUID id, UUID vocabularyId, UUID vocabularySenseId, String lemma, String definitionEn, Instant addedAt) {}
