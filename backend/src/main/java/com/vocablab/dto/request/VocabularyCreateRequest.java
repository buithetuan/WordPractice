package com.vocablab.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record VocabularyCreateRequest(
        @NotBlank @Size(max = 12) String language,
        @NotBlank @Size(max = 200) String lemma,
        @NotNull @Valid @Size(max = 50) List<SenseRequest> senses) {
    public record SenseRequest(
            @NotBlank String partOfSpeech,
            @Size(max = 10000) String definitionEn,
            @Size(max = 10000) String explanationVi,
            String cefrLevel,
            @Size(max = 120) String topic,
            @Size(max = 40) String register,
            @Valid List<ExampleRequest> examples) {}

    public record ExampleRequest(@NotBlank String sentenceEn, String translationVi) {}
}
