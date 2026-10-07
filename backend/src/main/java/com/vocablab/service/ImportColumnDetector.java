package com.vocablab.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ImportColumnDetector {
    private static final Pattern IPA_MARKS = Pattern.compile("[ˈˌəɜɑɔʊɪʃθðŋæɛɹʒ]");
    private static final Pattern VIETNAMESE = Pattern.compile("[ăâđêôơưĂÂĐÊÔƠƯ]");
    private final TextNormalizationService normalization;

    public ImportColumnDetector(TextNormalizationService normalization) {
        this.normalization = normalization;
    }

    public List<ColumnMapping> detect(List<String> headers, List<List<String>> rows) {
        List<ColumnMapping> result = new ArrayList<>();
        java.util.Set<SemanticField> assigned = new java.util.HashSet<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index) == null ? "" : headers.get(index).trim();
            Candidate candidate = fromHeader(header);
            if (candidate == null) candidate = fromSamples(index, rows);
            if (candidate != null && candidate.confidence >= 0.55 && assigned.add(candidate.field)) {
                result.add(new ColumnMapping(candidate.field, index, header, candidate.confidence,
                        candidate.confidence < 0.8));
            } else {
                result.add(new ColumnMapping(SemanticField.IGNORE, index, header, 1.0, false));
            }
        }
        return result;
    }

    private Candidate fromHeader(String header) {
        String key = normalization.normalizedHeader(header);
        Map<String, SemanticField> aliases = new LinkedHashMap<>();
        add(aliases, SemanticField.WORD, "word", "vocabulary", "lemma", "tu", "tu vung", "tu tieng anh", "headword");
        add(aliases, SemanticField.IPA, "ipa", "phonetic", "phonetics", "pronunciation", "phien am");
        add(aliases, SemanticField.MEANING_VI, "meaning vi", "meaning", "translation vi", "nghia", "nghia tieng viet", "dich nghia");
        add(aliases, SemanticField.DEFINITION_EN, "definition en", "definition", "english definition", "dinh nghia en", "dinh nghia tieng anh");
        add(aliases, SemanticField.POS, "pos", "part of speech", "word class", "tu loai", "loai tu", "tu loai tieng anh");
        add(aliases, SemanticField.EXAMPLE, "example", "examples", "sentence", "vi du", "cau vi du");
        add(aliases, SemanticField.TOPIC, "topic", "category", "chu de", "chu de tu vung");
        if (key.isBlank()) return null;
        SemanticField exact = aliases.get(key);
        if (exact != null) return new Candidate(exact, exact == SemanticField.MEANING_VI && key.equals("meaning") ? 0.78 : 0.99);
        if (key.equals("translation")) return new Candidate(SemanticField.MEANING_VI, 0.68);
        if (key.contains("meaning") || key.contains("translation") || key.contains("nghia")) return new Candidate(SemanticField.MEANING_VI, 0.68);
        if (key.contains("definition") || key.contains("dinh nghia")) return new Candidate(SemanticField.DEFINITION_EN, 0.76);
        if (key.contains("pronunciation") || key.contains("phien am")) return new Candidate(SemanticField.IPA, 0.73);
        return null;
    }

    private Candidate fromSamples(int index, List<List<String>> rows) {
        List<String> samples = rows.stream().limit(20).map(row -> index < row.size() ? row.get(index).trim() : "")
                .filter(value -> !value.isBlank()).toList();
        if (samples.isEmpty()) return null;
        long posCount = samples.stream().filter(ImportColumnDetector::looksLikePos).count();
        if (posCount >= Math.max(1, Math.ceil(samples.size() * 0.75))) return new Candidate(SemanticField.POS, 0.72);
        long ipaCount = samples.stream().filter(value -> IPA_MARKS.matcher(value).find() && !VIETNAMESE.matcher(value).find()).count();
        if (ipaCount >= Math.max(1, Math.ceil(samples.size() * 0.5))) return new Candidate(SemanticField.IPA, 0.67);
        long vietnameseCount = samples.stream().filter(ImportColumnDetector::looksVietnamese).count();
        if (vietnameseCount >= Math.max(1, Math.ceil(samples.size() * 0.75))) return new Candidate(SemanticField.MEANING_VI, 0.58);
        return null;
    }

    private static boolean looksLikePos(String value) {
        String text = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return List.of("noun", "n", "verb", "v", "adjective", "adj", "adverb", "adv", "pronoun", "preposition", "conjunction")
                .contains(text);
    }

    private static boolean looksVietnamese(String value) {
        String decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        long letters = value.codePoints().filter(Character::isLetter).count();
        long marks = decomposed.codePoints().filter(Character::isLetter).count();
        return VIETNAMESE.matcher(value).find() || marks > letters;
    }

    private static void add(Map<String, SemanticField> aliases, SemanticField field, String... values) {
        for (String value : values) aliases.put(value, field);
    }

    public enum SemanticField { WORD, IPA, MEANING_VI, DEFINITION_EN, POS, EXAMPLE, TOPIC, IGNORE }
    public record ColumnMapping(SemanticField field, int columnIndex, String header, double confidence, boolean reviewRequired) {}
    private record Candidate(SemanticField field, double confidence) {}
}
