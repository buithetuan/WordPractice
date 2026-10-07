package com.vocablab.service;

import com.vocablab.dto.request.PronunciationRequest;
import com.vocablab.exception.ApiException;
import com.vocablab.repository.VocabularyRepository;
import com.vocablab.repository.VocabularyRepository.StoredPronunciation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PronunciationService {
    private final VocabularyRepository repository;
    private final AudioStorageService storage;
    private final CurrentUserProvider currentUser;

    public PronunciationService(VocabularyRepository repository, AudioStorageService storage, CurrentUserProvider currentUser) {
        this.repository = repository;
        this.storage = storage;
        this.currentUser = currentUser;
    }

    @Transactional
    public UUID saveMetadata(UUID vocabularyId, PronunciationRequest request) {
        validateAccent(request.accent());
        return repository.savePronunciation(vocabularyId, request.vocabularySenseId(), request.accent(),
                request.ipa(), request.stressPattern(), null, null, currentUser.userId(), currentUser.isAdmin());
    }

    @Transactional
    public UUID upload(UUID vocabularyId, String senseId, String accent, String ipa,
            String stressPattern, MultipartFile file) {
        UUID sense = senseId == null || senseId.isBlank() ? null : UUID.fromString(senseId);
        validateAccent(accent);
        AudioStorageService.StoredAudio audio;
        try {
            audio = storage.store(vocabularyId, file);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store audio file");
        }
        try {
            return repository.savePronunciation(vocabularyId, sense, accent, ipa, stressPattern,
                    audio.relativePath(), audio.contentType(), currentUser.userId(), currentUser.isAdmin());
        } catch (RuntimeException exception) {
            try { storage.delete(audio.relativePath()); } catch (IOException ignored) { }
            throw exception;
        }
    }

    public AudioFile audio(UUID pronunciationId) {
        StoredPronunciation stored = repository.pronunciationAudio(pronunciationId);
        if (stored == null || stored.audioPath() == null) throw new ApiException(HttpStatus.NOT_FOUND, "Audio file not found");
        Path path = storage.resolve(stored.audioPath());
        if (!Files.isRegularFile(path)) throw new ApiException(HttpStatus.NOT_FOUND, "Audio file not found");
        return new AudioFile(path, stored.contentType() == null ? "application/octet-stream" : stored.contentType());
    }

    public record AudioFile(Path path, String contentType) {}

    private static void validateAccent(String accent) {
        if (!"UK".equals(accent) && !"US".equals(accent))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Accent must be UK or US");
    }
}
