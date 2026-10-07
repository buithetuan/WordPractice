package com.vocablab.controller;

import com.vocablab.dto.request.VocabularySetCreateRequest;
import com.vocablab.dto.request.VocabularySetItemRequest;
import com.vocablab.dto.request.VocabularySetUpdateRequest;
import com.vocablab.dto.response.VocabularySetItemResponse;
import com.vocablab.dto.response.VocabularySetResponse;
import com.vocablab.service.VocabularySetService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary-sets")
public class VocabularySetController {
    private final VocabularySetService service;

    public VocabularySetController(VocabularySetService service) {
        this.service = service;
    }

    @GetMapping
    public List<VocabularySetResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@Valid @RequestBody VocabularySetCreateRequest request) {
        return Map.of("id", service.create(request.name(), request.description()));
    }

    @PutMapping("/{setId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID setId, @Valid @RequestBody VocabularySetUpdateRequest request) {
        service.update(setId, request.name(), request.description());
    }

    @DeleteMapping("/{setId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID setId) {
        service.delete(setId);
    }

    @GetMapping("/{setId}/items")
    public List<VocabularySetItemResponse> items(@PathVariable UUID setId) {
        return service.items(setId);
    }

    @PostMapping("/{setId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> addItem(@PathVariable UUID setId, @Valid @RequestBody VocabularySetItemRequest request) {
        return Map.of("id", service.addItem(setId, request.vocabularyId(), request.vocabularySenseId()));
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable UUID itemId) {
        service.removeItem(itemId);
    }

    @PostMapping("/items/{itemId}/exclude")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excludeItem(@PathVariable UUID itemId) {
        service.exclude(itemId);
    }

    @DeleteMapping("/items/{itemId}/exclude")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreExclusion(@PathVariable UUID itemId) {
        service.restore(itemId);
    }
}
