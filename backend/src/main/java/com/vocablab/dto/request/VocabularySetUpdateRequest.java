package com.vocablab.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VocabularySetUpdateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 5000) String description) {}
