package com.vocablab.service;

import com.vocablab.dto.response.VocabularySetItemResponse;
import com.vocablab.dto.response.VocabularySetResponse;
import com.vocablab.repository.VocabularySetRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VocabularySetService {
    private final VocabularySetRepository repository;
    private final CurrentUserProvider currentUser;

    public VocabularySetService(VocabularySetRepository repository, CurrentUserProvider currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public List<VocabularySetResponse> list() {
        return repository.list(currentUser.userId());
    }

    public UUID create(String name, String description) {
        return repository.create(name, description, currentUser.userId());
    }

    public void update(UUID setId, String name, String description) {
        repository.update(setId, name, description, currentUser.userId(), currentUser.isAdmin());
    }

    public List<VocabularySetItemResponse> items(UUID setId) {
        return repository.items(setId, currentUser.userId());
    }

    public UUID addItem(UUID setId, UUID vocabularyId, UUID senseId) {
        return repository.addItem(setId, vocabularyId, senseId, currentUser.userId(), currentUser.isAdmin());
    }

    public void removeItem(UUID itemId) {
        repository.removeItem(itemId, currentUser.userId(), currentUser.isAdmin());
    }

    public void delete(UUID setId) {
        repository.deleteSet(setId, currentUser.userId(), currentUser.isAdmin());
    }

    public void exclude(UUID itemId) {
        repository.excludeItem(itemId, currentUser.userId());
    }

    public void restore(UUID itemId) {
        repository.restoreItem(itemId, currentUser.userId());
    }
}
