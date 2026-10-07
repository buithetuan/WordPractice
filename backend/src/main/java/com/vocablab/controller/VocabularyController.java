package com.vocablab.controller;

import com.vocablab.dto.request.VocabularyCreateRequest;
import com.vocablab.dto.request.VocabularyUpdateRequest;
import com.vocablab.dto.response.VocabularyDetailResponse;
import com.vocablab.dto.response.VocabularySummaryResponse;
import com.vocablab.service.VocabularyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabularies")
public class VocabularyController {
    private final VocabularyService service;

    public VocabularyController(VocabularyService service) {
        this.service = service;
    }

    @GetMapping
    public List<VocabularySummaryResponse> search(@RequestParam(required = false) String q) {
        return service.search(q);
    }

    @GetMapping("/{id}")
    public VocabularyDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VocabularyDetailResponse create(@Valid @RequestBody VocabularyCreateRequest request) {
        return service.create(request, "USER");
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID id, @Valid @RequestBody VocabularyUpdateRequest request) {
        service.update(id, request.lemma());
    }
}
