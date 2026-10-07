package com.vocablab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocablab.dto.request.EnrichmentSuggestionRequest;
import com.vocablab.dto.response.EnrichmentSuggestionResponse;
import com.vocablab.dto.response.VocabularyDetailResponse;
import com.vocablab.exception.ApiException;
import com.vocablab.repository.EnrichmentRepository;
import com.vocablab.repository.VocabularyRepository;
import com.vocablab.service.impl.EnrichmentProviderClient;
import com.vocablab.service.impl.EnrichmentProviderClient.ExistingSense;
import com.vocablab.service.impl.EnrichmentProviderClient.ProviderRequest;
import com.vocablab.service.impl.EnrichmentProviderClient.ProviderResponse;
import com.vocablab.service.impl.EnrichmentProviderClient.Suggestion;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EnrichmentService {
    private static final Set<String> POS = Set.of("NOUN", "VERB", "ADJECTIVE", "ADVERB", "PRONOUN",
            "PREPOSITION", "CONJUNCTION", "INTERJECTION", "DETERMINER", "NUMERAL", "PARTICLE", "PHRASE");
    private static final Set<String> CEFR = Set.of("A1", "A2", "B1", "B2", "C1", "C2");
    private final CurrentUserProvider currentUser;
    private final VocabularyRepository vocabularies;
    private final EnrichmentProviderClient provider;
    private final EnrichmentRepository enrichments;
    private final ObjectMapper objectMapper;

    public EnrichmentService(CurrentUserProvider currentUser, VocabularyRepository vocabularies,
            EnrichmentProviderClient provider, EnrichmentRepository enrichments, ObjectMapper objectMapper) {
        this.currentUser = currentUser;
        this.vocabularies = vocabularies;
        this.provider = provider;
        this.enrichments = enrichments;
        this.objectMapper = objectMapper;
    }

    public EnrichmentSuggestionResponse suggest(EnrichmentSuggestionRequest request) {
        VocabularyDetailResponse word = vocabularies.detail(request.vocabularyId());
        VocabularyDetailResponse.Sense selected = null;
        if (request.vocabularySenseId() != null) {
            selected = word.senses().stream().filter(sense -> sense.id().equals(request.vocabularySenseId())).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Sense does not belong to the vocabulary"));
        }
        List<ExistingSense> existing = word.senses().stream()
                .map(sense -> new ExistingSense(sense.partOfSpeech(), sense.definitionEn(), sense.explanationVi())).toList();
        String pos = request.partOfSpeech() == null && selected != null ? selected.partOfSpeech() : request.partOfSpeech();
        ProviderRequest input = new ProviderRequest(word.lemma(), pos,
                selected == null ? null : selected.definitionEn(), selected == null ? null : selected.explanationVi(), existing);
        ProviderResponse response = provider.suggest(input);
        validate(response);
        try {
            UUID jobId = enrichments.saveSuggestion(currentUser.userId(), word.id(),
                    selected == null ? null : selected.id(), objectMapper.writeValueAsString(input), response);
            return toResponse(jobId, response);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store enrichment request");
        }
    }

    public UUID apply(UUID jobId, List<String> fields, Integer suggestionIndex) {
        List<String> normalizedFields = fields.stream().map(field -> field.toUpperCase(Locale.ROOT)).distinct().toList();
        return enrichments.apply(jobId, currentUser.userId(), normalizedFields, suggestionIndex);
    }

    public void reject(UUID jobId) {
        enrichments.reject(jobId, currentUser.userId());
    }

    private static void validate(ProviderResponse response) {
        if (response.provider() == null || response.model() == null || response.suggestions() == null
                || response.suggestions().isEmpty() || response.suggestions().size() > 20) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned an invalid suggestion shape");
        }
        for (Suggestion suggestion : response.suggestions()) {
            if (suggestion.partOfSpeech() == null || !POS.contains(suggestion.partOfSpeech().toUpperCase(Locale.ROOT))) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned an invalid part of speech");
            }
            if (suggestion.cefrLevel() != null && !CEFR.contains(suggestion.cefrLevel().toUpperCase(Locale.ROOT))) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned an invalid CEFR level");
            }
            if (suggestion.definitionEn() != null && suggestion.definitionEn().length() > 10000
                    || suggestion.explanationVi() != null && suggestion.explanationVi().length() > 10000) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned a field that is too long");
            }
        }
    }

    private static EnrichmentSuggestionResponse toResponse(UUID id, ProviderResponse response) {
        return new EnrichmentSuggestionResponse(id, response.provider(), response.model(), response.ambiguous(),
                response.suggestions().stream().map(item -> new EnrichmentSuggestionResponse.Suggestion(
                        item.partOfSpeech(), item.definitionEn(), item.explanationVi(), item.cefrLevel(),
                        item.topic(), item.examples() == null ? List.of() : item.examples(),
                        item.relatedWords() == null ? List.of() : item.relatedWords())).toList());
    }
}
