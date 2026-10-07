package com.vocablab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocablab.dto.request.ImportConfirmRequest;
import com.vocablab.dto.request.VocabularyCreateRequest;
import com.vocablab.dto.response.ImportPreviewResponse;
import com.vocablab.dto.response.ImportResultResponse;
import com.vocablab.dto.response.VocabularyDetailResponse;
import com.vocablab.exception.ApiException;
import com.vocablab.repository.ImportRepository;
import com.vocablab.repository.ImportRepository.MappingSnapshot;
import com.vocablab.repository.ImportRepository.NewRow;
import com.vocablab.repository.ImportRepository.StoredJob;
import com.vocablab.repository.ImportRepository.StoredRow;
import com.vocablab.repository.VocabularyRepository;
import com.vocablab.repository.VocabularySetRepository;
import com.vocablab.service.ImportColumnDetector.ColumnMapping;
import com.vocablab.service.ImportColumnDetector.SemanticField;
import com.vocablab.service.TabularFileParser.ParsedFile;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SmartImportService {
    private final CurrentUserProvider currentUser;
    private final TabularFileParser parser;
    private final ImportColumnDetector detector;
    private final TextNormalizationService normalization;
    private final ImportRepository imports;
    private final VocabularyRepository vocabularies;
    private final VocabularySetRepository sets;
    private final ObjectMapper objectMapper;

    public SmartImportService(CurrentUserProvider currentUser, TabularFileParser parser,
            ImportColumnDetector detector, TextNormalizationService normalization, ImportRepository imports,
            VocabularyRepository vocabularies, VocabularySetRepository sets, ObjectMapper objectMapper) {
        this.currentUser = currentUser;
        this.parser = parser;
        this.detector = detector;
        this.normalization = normalization;
        this.imports = imports;
        this.vocabularies = vocabularies;
        this.sets = sets;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportPreviewResponse preview(MultipartFile file, UUID targetSetId) {
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Upload a non-empty CSV or XLSX file");
        if (file.getSize() > 10 * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Import file exceeds 10 MB");
        try {
            String filename = safeFilename(file.getOriginalFilename());
            ParsedFile parsed = parser.parse(file.getBytes(), filename);
            List<String> headers = parsed.headers().stream().map(value -> normalization.normalizeForReview(value).text()).toList();
            List<List<String>> normalizedRows = new ArrayList<>();
            List<Map<String, String>> repairRows = new ArrayList<>();
            List<List<String>> repairColumns = new ArrayList<>();
            for (List<String> row : parsed.rows()) {
                List<String> normalized = new ArrayList<>();
                Map<String, String> suggestions = new LinkedHashMap<>();
                List<String> repairIndexes = new ArrayList<>();
                for (int column = 0; column < headers.size(); column++) {
                    String original = column < row.size() ? row.get(column) : "";
                    TextNormalizationService.NormalizedText value = normalization.normalizeForReview(original);
                    normalized.add(value.text());
                    if (value.repairProposed()) {
                        repairIndexes.add(Integer.toString(column));
                        if (value.repairedSuggestion() != null) suggestions.put(Integer.toString(column), value.repairedSuggestion());
                    }
                }
                normalizedRows.add(normalized);
                repairRows.add(suggestions);
                repairColumns.add(repairIndexes);
            }
            List<ColumnMapping> mappings = detector.detect(headers, normalizedRows);
            Map<String, Integer> initialMapping = toMapping(mappings);
            if (!initialMapping.containsKey("WORD")) throw new ApiException(HttpStatus.BAD_REQUEST, "Could not detect a WORD column; confirm a mapping in the file first");
            List<NewRow> rows = new ArrayList<>();
            List<ImportPreviewResponse.Row> responseRows = new ArrayList<>();
            int valid = 0, ambiguous = 0, invalid = 0;
            for (int i = 0; i < normalizedRows.size(); i++) {
                List<String> values = normalizedRows.get(i);
                Map<String, String> mappedValues = valuesByField(initialMapping, values);
                List<String> issues = validateRow(mappedValues, parsed.encodingRepairProposed(), repairColumns.get(i), initialMapping);
                String status = statusFor(issues);
                if (status.equals("VALID")) valid++;
                else if (status.equals("AMBIGUOUS")) ambiguous++;
                else invalid++;
                Map<String, Object> raw = Map.of("cells", i < parsed.rows().size() ? parsed.rows().get(i) : List.of());
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("cells", values);
                normalized.put("repairSuggestions", repairRows.get(i));
                normalized.put("repairRequiredColumns", repairColumns.get(i));
                normalized.put("initialStatus", status);
                rows.add(new NewRow(i + 2, raw, normalized, status, issues));
                responseRows.add(new ImportPreviewResponse.Row(i + 2, mappedValues, status, issues, repairRows.get(i)));
            }
            List<Map<String, Object>> storedMappings = mappings.stream().map(mapping -> Map.<String, Object>of(
                    "field", mapping.field().name(), "columnIndex", mapping.columnIndex(), "header", mapping.header(),
                    "confidence", mapping.confidence(), "reviewRequired", mapping.reviewRequired())).toList();
            Map<String, Object> mappingSnapshot = Map.of("headers", headers, "columns", storedMappings);
            UUID jobId = UUID.randomUUID();
            imports.create(jobId, currentUser.userId(), targetSetId, filename, sha256(file.getBytes()),
                    parsed.encoding(), parsed.encodingRepairProposed(), mappingSnapshot, rows);
            return new ImportPreviewResponse(jobId, filename, parsed.encoding(), parsed.encodingRepairProposed(),
                    mappings.stream().map(mapping -> new ImportPreviewResponse.Column(mapping.field().name(),
                            mapping.columnIndex(), mapping.header(), mapping.confidence(), mapping.reviewRequired())).toList(),
                    responseRows, valid, ambiguous, invalid);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not read import file");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @Transactional
    public ImportResultResponse confirm(UUID jobId, ImportConfirmRequest request) {
        UUID userId = currentUser.userId();
        StoredJob job = imports.get(jobId, userId);
        if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "Import preview not found");
        if (!"PREVIEW_READY".equals(job.status())) throw new ApiException(HttpStatus.CONFLICT, "Import preview has already been confirmed");

        MappingSnapshot snapshot = imports.mapping(jobId, userId);
        Map<String, Integer> mapping = request.mapping() == null || request.mapping().isEmpty()
                ? snapshot.fields() : normalizeMapping(request.mapping());
        if (!mapping.containsKey("WORD")) throw new ApiException(HttpStatus.BAD_REQUEST, "Mapping must include WORD");
        validateMapping(mapping, snapshot.headers().size());
        UUID setId = job.setId();
        if (setId != null) sets.items(setId, userId); // Enforce private-set visibility before writing any rows.

        List<StoredRow> storedRows = imports.rows(jobId);
        List<ImportResultResponse.RowResult> rowResults = new ArrayList<>();
        int imported = 0;
        int rejected = 0;
        for (StoredRow storedRow : storedRows) {
            Map<String, String> cellRepairs = stringMap(storedRow.normalized().path("repairSuggestions"));
            List<String> repairColumns = stringList(storedRow.normalized().path("repairRequiredColumns"));
            List<String> cells = stringList(storedRow.normalized().path("cells"));
            Map<String, String> values = valuesByField(mapping, cells);
            List<String> issues = new ArrayList<>();
            if (job.repairProposed() && !request.acceptEncodingRepairs()) issues.add("ENCODING_REPAIR_NOT_ACCEPTED");
            for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
                String column = Integer.toString(entry.getValue());
                if (!issues.contains("ENCODING_REPAIR_NOT_ACCEPTED") && repairColumns.contains(column)) {
                    if (!request.acceptEncodingRepairs()) {
                        issues.add("ENCODING_REPAIR_NOT_ACCEPTED");
                    } else if (cellRepairs.containsKey(column)) {
                        values.put(entry.getKey(), cellRepairs.get(column));
                    } else if ("WINDOWS-1252".equals(job.encoding())) {
                        // The decoded CSV is itself a proposed repair and was visible in the preview.
                    } else {
                        issues.add("ENCODING_REPAIR_REQUIRES_REVIEW");
                    }
                }
            }
            issues.addAll(validateMappedValues(values));
            String pos = firstNonBlank(request.rowPartOfSpeechOverrides() == null ? null
                            : request.rowPartOfSpeechOverrides().get(storedRow.rowNumber()), values.get("POS"));
            String posCode = normalizePos(pos);
            String meaningVi = blankToNull(values.get("MEANING_VI"));
            String definitionEn = blankToNull(values.get("DEFINITION_EN"));
            if (pos != null && posCode == null) issues.add("UNKNOWN_PART_OF_SPEECH");
            if (posCode == null && (meaningVi != null || definitionEn != null)) issues.add("PART_OF_SPEECH_REQUIRED_TO_RESOLVE_SENSE");
            String status = issues.isEmpty() ? "VALID" : issues.stream().anyMatch(issue -> issue.contains("REQUIRED") || issue.contains("AMBIGUOUS") || issue.contains("REPAIR")) ? "AMBIGUOUS" : "INVALID";
            UUID vocabularyId = null;
            UUID senseId = null;
            if (status.equals("VALID")) {
                List<VocabularyCreateRequest.ExampleRequest> examples = splitExamples(values.get("EXAMPLE"));
                List<VocabularyCreateRequest.SenseRequest> senses = posCode == null ? List.of() : List.of(
                        new VocabularyCreateRequest.SenseRequest(posCode, definitionEn, meaningVi, null,
                                blankToNull(values.get("TOPIC")), null, examples));
                String lemma = normalization.normalizeForReview(values.get("WORD")).text();
                UUID createdId = vocabularies.createOrAdd(new VocabularyCreateRequest("en", lemma, senses), userId, "USER_IMPORT");
                VocabularyDetailResponse created = vocabularies.detail(createdId);
                vocabularyId = created.id();
                if (posCode != null) {
                    String expectedDefinition = definitionEn;
                    String expectedMeaning = meaningVi;
                    senseId = created.senses().stream().filter(sense -> sense.partOfSpeech().equals(posCode)
                                    && java.util.Objects.equals(sense.definitionEn(), expectedDefinition)
                                    && java.util.Objects.equals(sense.explanationVi(), expectedMeaning))
                            .map(VocabularyDetailResponse.Sense::id).findFirst().orElse(null);
                }
                if (setId == null) {
                    setId = sets.create(job.filename().replaceFirst("(?i)\\.(csv|xlsx|xls)$", ""),
                            "Imported from " + job.filename(), userId);
                }
                try {
                    sets.addItem(setId, vocabularyId, senseId, userId, currentUser.isAdmin());
                } catch (ApiException exception) {
                    if (exception.getStatus() != HttpStatus.CONFLICT) throw exception;
                }
                imported++;
                status = "IMPORTED";
            } else {
                rejected++;
            }
            imports.updateRow(jobId, storedRow.rowNumber(), status, issues, vocabularyId, senseId);
            rowResults.add(new ImportResultResponse.RowResult(storedRow.rowNumber(), status, issues));
        }
        String finalStatus = rejected == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
        imports.finish(jobId, setId, finalStatus, imported, rejected);
        return new ImportResultResponse(jobId, setId, finalStatus, imported, rejected, rowResults);
    }

    private Map<String, Integer> normalizeMapping(Map<String, Integer> input) {
        Map<String, Integer> result = new LinkedHashMap<>();
        input.forEach((field, column) -> {
            try {
                SemanticField semantic = SemanticField.valueOf(field.toUpperCase(Locale.ROOT));
                if (semantic != SemanticField.IGNORE) result.put(semantic.name(), column);
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown mapping field: " + field);
            }
        });
        return result;
    }

    private void validateMapping(Map<String, Integer> mapping, int columnCount) {
        List<Integer> indexes = mapping.values().stream().toList();
        if (indexes.stream().anyMatch(index -> index == null || index < 0 || index >= columnCount))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Column index is out of range");
        if (indexes.size() != indexes.stream().distinct().count())
            throw new ApiException(HttpStatus.BAD_REQUEST, "A source column cannot map to more than one field");
    }

    private Map<String, Integer> toMapping(List<ColumnMapping> columns) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ColumnMapping column : columns) if (column.field() != SemanticField.IGNORE)
            result.putIfAbsent(column.field().name(), column.columnIndex());
        return result;
    }

    private Map<String, String> valuesByField(Map<String, Integer> mapping, List<String> row) {
        Map<String, String> result = new LinkedHashMap<>();
        mapping.forEach((field, index) -> result.put(field, index < row.size() ? row.get(index) : ""));
        return result;
    }

    private List<String> validateRow(Map<String, String> values, boolean encodingRepair,
            List<String> repairColumns, Map<String, Integer> mapping) {
        List<String> issues = validateMappedValues(values);
        if (encodingRepair) issues.add("FILE_ENCODING_REVIEW_REQUIRED");
        if (repairColumns.stream().anyMatch(column -> mapping.values().contains(Integer.valueOf(column))))
            issues.add("TEXT_REPAIR_PREVIEW_REQUIRED");
        if (((values.get("MEANING_VI") != null && !values.get("MEANING_VI").isBlank())
                || (values.get("DEFINITION_EN") != null && !values.get("DEFINITION_EN").isBlank()))
                && (values.get("POS") == null || values.get("POS").isBlank()))
            issues.add("PART_OF_SPEECH_REVIEW_REQUIRED");
        return issues;
    }

    private List<String> validateMappedValues(Map<String, String> values) {
        List<String> issues = new ArrayList<>();
        String word = values.get("WORD");
        if (word == null || word.isBlank()) issues.add("WORD_IS_REQUIRED");
        else if (word.trim().length() > 200) issues.add("WORD_EXCEEDS_200_CHARACTERS");
        String ipa = values.get("IPA");
        if (ipa != null && looksVietnameseInIpa(ipa)) issues.add("VIETNAMESE_TEXT_IN_IPA_COLUMN");
        return issues;
    }

    private String statusFor(List<String> issues) {
        if (issues.isEmpty()) return "VALID";
        if (issues.stream().anyMatch(issue -> issue.contains("REVIEW"))) return "AMBIGUOUS";
        return "INVALID";
    }

    private String normalizePos(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = normalization.normalizedHeader(value).replaceAll("[^a-z ]", "").trim();
        return switch (normalized) {
            case "n", "noun", "danh tu" -> "NOUN";
            case "v", "verb", "dong tu" -> "VERB";
            case "adj", "adjective", "tinh tu" -> "ADJECTIVE";
            case "adv", "adverb", "trang tu" -> "ADVERB";
            case "pron", "pronoun", "dai tu" -> "PRONOUN";
            case "prep", "preposition", "gioi tu" -> "PREPOSITION";
            case "conj", "conjunction", "lien tu" -> "CONJUNCTION";
            case "interjection", "than tu" -> "INTERJECTION";
            case "determiner" -> "DETERMINER";
            case "numeral", "number" -> "NUMERAL";
            case "particle" -> "PARTICLE";
            case "phrase" -> "PHRASE";
            default -> null;
        };
    }

    private static List<VocabularyCreateRequest.ExampleRequest> splitExamples(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("\\s*[;\\n]\\s*"))
                .filter(part -> !part.isBlank()).limit(10)
                .map(part -> new VocabularyCreateRequest.ExampleRequest(part.trim(), null)).toList();
    }

    private static boolean looksVietnameseInIpa(String value) {
        String decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        return value.codePoints().anyMatch(codePoint -> "ăâđêôơưĂÂĐÊÔƠƯ".indexOf(codePoint) >= 0)
                || decomposed.codePoints().anyMatch(codePoint -> codePoint == 0x0300 || codePoint == 0x0301
                        || codePoint == 0x0303 || codePoint == 0x0309 || codePoint == 0x0323);
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "vocabulary-import.csv";
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "vocabulary-import.csv" : name.substring(0, Math.min(name.length(), 255));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(value -> result.add(value.asText()));
        return result;
    }
}
