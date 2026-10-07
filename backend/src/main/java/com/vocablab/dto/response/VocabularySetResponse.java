package com.vocablab.dto.response;

import java.time.Instant;
import java.util.UUID;

public record VocabularySetResponse(UUID id, String name, String description, String visibility, int itemCount, Instant createdAt) {}
