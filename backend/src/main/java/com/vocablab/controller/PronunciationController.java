package com.vocablab.controller;

import com.vocablab.dto.request.PronunciationRequest;
import com.vocablab.service.PronunciationService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class PronunciationController {
    private final PronunciationService service;

    public PronunciationController(PronunciationService service) {
        this.service = service;
    }

    @PostMapping("/vocabularies/{vocabularyId}/pronunciations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> saveMetadata(@PathVariable UUID vocabularyId,
            @Valid @RequestBody PronunciationRequest request) {
        return Map.of("id", service.saveMetadata(vocabularyId, request));
    }

    @PostMapping(value = "/vocabularies/{vocabularyId}/pronunciations/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> upload(@PathVariable UUID vocabularyId,
            @RequestParam String accent, @RequestParam(required = false) String vocabularySenseId,
            @RequestParam(required = false) String ipa, @RequestParam(required = false) String stressPattern,
            @RequestPart("file") MultipartFile file) {
        return Map.of("id", service.upload(vocabularyId, vocabularySenseId, accent, ipa, stressPattern, file));
    }

    @GetMapping("/pronunciations/{pronunciationId}/audio")
    public ResponseEntity<Resource> audio(@PathVariable UUID pronunciationId) {
        PronunciationService.AudioFile audio = service.audio(pronunciationId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(audio.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(audio.path()));
    }
}
