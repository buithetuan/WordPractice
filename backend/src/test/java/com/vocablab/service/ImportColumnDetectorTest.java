package com.vocablab.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vocablab.service.ImportColumnDetector.SemanticField;
import com.vocablab.service.impl.TextNormalizationServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportColumnDetectorTest {
    private final ImportColumnDetector detector = new ImportColumnDetector(new TextNormalizationServiceImpl());

    @Test
    void recognizesVietnameseHeadersIndependentOfColumnOrder() {
        var first = detector.detect(List.of("Từ vựng", "Nghĩa tiếng Việt", "Từ loại"),
                List.of(List.of("bank", "ngân hàng", "danh từ")));
        var reordered = detector.detect(List.of("Từ loại", "Từ vựng", "Nghĩa tiếng Việt"),
                List.of(List.of("danh từ", "bank", "ngân hàng")));

        assertEquals(0, mapping(first).get(SemanticField.WORD));
        assertEquals(1, mapping(first).get(SemanticField.MEANING_VI));
        assertEquals(2, mapping(first).get(SemanticField.POS));
        assertEquals(1, mapping(reordered).get(SemanticField.WORD));
        assertEquals(2, mapping(reordered).get(SemanticField.MEANING_VI));
        assertEquals(0, mapping(reordered).get(SemanticField.POS));
    }

    @Test
    void marksGenericMeaningHeaderAsNeedingReview() {
        var columns = detector.detect(List.of("Word", "Meaning"), List.of(List.of("bank", "ngân hàng")));
        var meaning = columns.stream().filter(item -> item.field() == SemanticField.MEANING_VI).findFirst().orElseThrow();
        assertTrue(meaning.reviewRequired());
        assertTrue(meaning.confidence() < 0.8);
    }

    private static java.util.Map<SemanticField, Integer> mapping(List<ImportColumnDetector.ColumnMapping> columns) {
        return columns.stream().collect(java.util.stream.Collectors.toMap(
                ImportColumnDetector.ColumnMapping::field, ImportColumnDetector.ColumnMapping::columnIndex));
    }
}
