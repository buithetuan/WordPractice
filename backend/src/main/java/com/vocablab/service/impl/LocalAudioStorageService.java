package com.vocablab.service.impl;

import com.vocablab.exception.ApiException;
import com.vocablab.service.AudioStorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalAudioStorageService implements AudioStorageService {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "audio/mpeg", ".mp3", "audio/mp3", ".mp3", "audio/wav", ".wav",
            "audio/x-wav", ".wav", "audio/ogg", ".ogg", "audio/webm", ".webm");
    private final Path root;

    public LocalAudioStorageService(@Value("${spring.audio.storage-path:./var/audio}") String storagePath) {
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public StoredAudio store(UUID vocabularyId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Audio file is empty");
        if (file.getSize() > 10 * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio file exceeds 10 MB");
        String type = file.getContentType() == null ? "" : file.getContentType().split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        String extension = EXTENSIONS.get(type);
        if (extension == null) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Supported audio types: MP3, WAV, OGG, WEBM");
        String relative = vocabularyId + "/" + UUID.randomUUID() + extension;
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, file.getBytes());
        return new StoredAudio(relative, type);
    }

    @Override
    public Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid audio path");
        return target;
    }

    @Override
    public void delete(String relativePath) throws IOException {
        if (relativePath != null) Files.deleteIfExists(resolve(relativePath));
    }
}
