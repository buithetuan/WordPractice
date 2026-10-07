package com.vocablab.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ImportPreviewResponse(
        UUID jobId,
        String filename,
        String detectedEncoding,
        boolean encodingRepairProposed,
        List<Column> columns,
        List<Row> rows,
        int validCount,
        int ambiguousCount,
        int invalidCount) {
    public record Column(String field, int columnIndex, String header, double confidence, boolean reviewRequired) {}
    public record Row(int rowNumber, Map<String, String> values, String status, List<String> issues,
            Map<String, String> repairSuggestions) {}
}
