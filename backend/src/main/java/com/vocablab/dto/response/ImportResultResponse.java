package com.vocablab.dto.response;

import java.util.List;
import java.util.UUID;

public record ImportResultResponse(UUID jobId, UUID vocabularySetId, String status,
        int importedCount, int rejectedCount, List<RowResult> rows) {
    public record RowResult(int rowNumber, String status, List<String> issues) {}
}
