package com.vocablab.controller;

import com.vocablab.dto.request.ImportConfirmRequest;
import com.vocablab.dto.response.ImportPreviewResponse;
import com.vocablab.dto.response.ImportResultResponse;
import com.vocablab.service.SmartImportService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/imports")
public class SmartImportController {
    private final SmartImportService service;

    public SmartImportController(SmartImportService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportPreviewResponse preview(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID targetSetId) {
        return service.preview(file, targetSetId);
    }

    @PostMapping("/{jobId}/confirm")
    public ImportResultResponse confirm(@PathVariable UUID jobId, @Valid @RequestBody ImportConfirmRequest request) {
        return service.confirm(jobId, request);
    }
}
