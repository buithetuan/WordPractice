package com.vocablab.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record VocabularySetItemRequest(@NotNull UUID vocabularyId, UUID vocabularySenseId) {}
