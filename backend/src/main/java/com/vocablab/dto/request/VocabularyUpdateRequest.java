package com.vocablab.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VocabularyUpdateRequest(@NotBlank @Size(max = 200) String lemma) {}
