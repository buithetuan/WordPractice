package com.vocablab.service;

import com.vocablab.dto.request.VocabularyCreateRequest;
import com.vocablab.dto.response.VocabularyDetailResponse;
import com.vocablab.dto.response.VocabularySummaryResponse;
import com.vocablab.exception.ApiException;
import com.vocablab.repository.VocabularyRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VocabularyService {
    private final VocabularyRepository repository;
    private final CurrentUserProvider currentUser;
    private final TextNormalizationService normalization;

    public VocabularyService(VocabularyRepository repository, CurrentUserProvider currentUser,
            TextNormalizationService normalization) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.normalization = normalization;
    }

    public List<VocabularySummaryResponse> search(String query) {
        return repository.search(query == null ? null : normalization.normalizeLemma(query));
    }

    public VocabularyDetailResponse detail(UUID id) {
        return repository.detail(id);
    }

    @Transactional
    public VocabularyDetailResponse create(VocabularyCreateRequest request, String sourceType) {
        if (request.lemma().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Lemma must not be blank");
        VocabularyCreateRequest normalized = normalize(request);
        UUID id = repository.createOrAdd(normalized, currentUser.userId(), sourceType);
        return repository.detail(id);
    }

    public void update(UUID id, String lemma) {
        String repairedLemma = normalization.normalizeForReview(lemma).text();
        if (repairedLemma.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Lemma must not be blank");
        repository.updateLemma(id, repairedLemma, currentUser.userId(), currentUser.isAdmin());
    }

    private VocabularyCreateRequest normalize(VocabularyCreateRequest request) {
        String lemma = normalization.normalizeForReview(request.lemma()).text();
        List<VocabularyCreateRequest.SenseRequest> senses = request.senses().stream().map(sense ->
                new VocabularyCreateRequest.SenseRequest(sense.partOfSpeech(),
                        nullableNormalized(sense.definitionEn()), nullableNormalized(sense.explanationVi()),
                        sense.cefrLevel(), nullableNormalized(sense.topic()), nullableNormalized(sense.register()),
                        sense.examples() == null ? List.of() : sense.examples().stream().map(example ->
                                new VocabularyCreateRequest.ExampleRequest(
                                        normalization.normalizeForReview(example.sentenceEn()).text(),
                                        nullableNormalized(example.translationVi()))).toList())).toList();
        return new VocabularyCreateRequest(request.language(), lemma, senses);
    }

    private String nullableNormalized(String input) {
        return input == null ? null : normalization.normalizeForReview(input).text();
    }
}
