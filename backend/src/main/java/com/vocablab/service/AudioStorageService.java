package com.vocablab.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface AudioStorageService {
    StoredAudio store(UUID vocabularyId, MultipartFile file) throws IOException;
    Path resolve(String relativePath);
    void delete(String relativePath) throws IOException;

    record StoredAudio(String relativePath, String contentType) {}
}
