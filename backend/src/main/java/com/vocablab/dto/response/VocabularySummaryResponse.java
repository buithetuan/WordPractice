package com.vocablab.dto.response;

import java.time.Instant;
import java.util.UUID;

public record VocabularySummaryResponse(UUID id, String language, String lemma, Instant createdAt) {}
