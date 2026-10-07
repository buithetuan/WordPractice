package com.vocablab.controller;

import com.vocablab.dto.request.EnrichmentApplyRequest;
import com.vocablab.dto.request.EnrichmentSuggestionRequest;
import com.vocablab.dto.response.EnrichmentSuggestionResponse;
import com.vocablab.service.EnrichmentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enrichment")
public class EnrichmentController {
    private final EnrichmentService service;

    public EnrichmentController(EnrichmentService service) {
        this.service = service;
    }

    @PostMapping("/suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrichmentSuggestionResponse suggest(@Valid @RequestBody EnrichmentSuggestionRequest request) {
        return service.suggest(request);
    }

    @PostMapping("/{jobId}/apply")
    public Map<String, UUID> apply(@PathVariable UUID jobId, @Valid @RequestBody EnrichmentApplyRequest request) {
        return Map.of("vocabularySenseId", service.apply(jobId, request.acceptedFields(), request.selectedSuggestionIndex()));
    }

    @PostMapping("/{jobId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID jobId) {
        service.reject(jobId);
    }
}
