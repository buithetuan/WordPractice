package com.vocablab.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PronunciationRequest(
        UUID vocabularySenseId,
        @NotBlank @Pattern(regexp = "UK|US") String accent,
        @Size(max = 255) String ipa,
        @Size(max = 120) String stressPattern) {}
