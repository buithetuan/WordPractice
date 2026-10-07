package com.vocablab.repository;

import com.vocablab.dto.request.VocabularyCreateRequest;
import com.vocablab.dto.response.VocabularyDetailResponse;
import com.vocablab.dto.response.VocabularySummaryResponse;
import com.vocablab.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VocabularyRepository {
    private final JdbcTemplate jdbc;

    public VocabularyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID createOrAdd(VocabularyCreateRequest request, UUID userId, String sourceType) {
        String language = request.language().trim().toLowerCase();
        String lemma = request.lemma().trim();
        String normalized = lemma.toLowerCase(java.util.Locale.ROOT);
        UUID vocabularyId = jdbc.queryForObject("""
                insert into vocabularies (language, lemma, normalized_lemma, created_by, source_type)
                values (?, ?, ?, ?, ?)
                on conflict (language, normalized_lemma)
                do update set updated_at = now()
                returning id
                """, UUID.class, language, lemma, normalized, userId, sourceType);
        Boolean ownVocabulary = jdbc.query("select created_by = ? and source_type = ? from vocabularies where id = ?",
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next() && rs.getBoolean(1),
                userId, sourceType, vocabularyId);
        if (Boolean.TRUE.equals(ownVocabulary)) {
            provenance(vocabularyId, null, "LEMMA", sourceType, userId);
        }

        for (VocabularyCreateRequest.SenseRequest sense : request.senses()) {
            Short partOfSpeechId = jdbc.query("select id from part_of_speech where code = ?",
                    rs -> rs.next() ? rs.getShort(1) : null, sense.partOfSpeech().trim().toUpperCase());
            if (partOfSpeechId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown part of speech: " + sense.partOfSpeech());
            }
            if (sense.cefrLevel() != null && !sense.cefrLevel().isBlank()
                    && !List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(sense.cefrLevel().trim().toUpperCase())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CEFR level must be A1, A2, B1, B2, C1 or C2");
            }
            UUID senseId = findMatchingSense(vocabularyId, partOfSpeechId, sense);
            if (senseId == null) {
                Short nextOrder = jdbc.queryForObject(
                        "select coalesce(max(sense_order), 0) + 1 from vocabulary_senses where vocabulary_id = ?",
                        Short.class, vocabularyId);
                senseId = jdbc.queryForObject("""
                        insert into vocabulary_senses
                            (vocabulary_id, part_of_speech_id, sense_order, definition_en, explanation_vi,
                             cefr_level, topic, register, created_by, source_type)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """, UUID.class, vocabularyId, partOfSpeechId, nextOrder,
                        blankToNull(sense.definitionEn()), blankToNull(sense.explanationVi()),
                        blankToNull(sense.cefrLevel() == null ? null : sense.cefrLevel().trim().toUpperCase()),
                        blankToNull(sense.topic()), blankToNull(sense.register()), userId, sourceType);
                provenance(null, senseId, "PART_OF_SPEECH", sourceType, userId);
                if (sense.definitionEn() != null && !sense.definitionEn().isBlank()) provenance(null, senseId, "DEFINITION_EN", sourceType, userId);
                if (sense.explanationVi() != null && !sense.explanationVi().isBlank()) provenance(null, senseId, "EXPLANATION_VI", sourceType, userId);
                if (sense.cefrLevel() != null && !sense.cefrLevel().isBlank()) provenance(null, senseId, "CEFR_LEVEL", sourceType, userId);
                if (sense.topic() != null && !sense.topic().isBlank()) provenance(null, senseId, "TOPIC", sourceType, userId);
                if (sense.register() != null && !sense.register().isBlank()) provenance(null, senseId, "REGISTER", sourceType, userId);
                int order = 1;
                if (sense.examples() != null) {
                    for (VocabularyCreateRequest.ExampleRequest example : sense.examples()) {
                        jdbc.update("""
                                insert into vocabulary_examples
                                    (vocabulary_sense_id, sentence_en, translation_vi, display_order, source_type)
                                values (?, ?, ?, ?, ?)
                                """, senseId, example.sentenceEn().trim(), blankToNull(example.translationVi()), order++, sourceType);
                    }
                    if (!sense.examples().isEmpty()) provenance(null, senseId, "EXAMPLES", sourceType, userId);
                }
            }
        }
        return vocabularyId;
    }

    private UUID findMatchingSense(UUID vocabularyId, Short posId, VocabularyCreateRequest.SenseRequest sense) {
        List<UUID> ids = jdbc.query("""
                select id from vocabulary_senses
                where vocabulary_id = ? and part_of_speech_id = ?
                  and coalesce(definition_en, '') = coalesce(?, '')
                  and coalesce(explanation_vi, '') = coalesce(?, '')
                limit 1
                """, (rs, row) -> rs.getObject(1, UUID.class), vocabularyId, posId,
                blankToNull(sense.definitionEn()), blankToNull(sense.explanationVi()));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public List<VocabularySummaryResponse> search(String search) {
        String term = search == null ? "" : search.trim().toLowerCase(java.util.Locale.ROOT);
        return jdbc.query("""
                select id, language, lemma, created_at from vocabularies
                where ? = '' or normalized_lemma like ?
                order by normalized_lemma limit 100
                """, SUMMARY_MAPPER, term, "%" + term + "%");
    }

    public VocabularyDetailResponse detail(UUID id) {
        VocabularySummaryResponse word = jdbc.query("""
                select id, language, lemma, created_at from vocabularies where id = ?
                """, SUMMARY_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vocabulary not found"));

        List<VocabularyDetailResponse.Sense> senses = jdbc.query("""
                select s.id, p.code as part_of_speech, s.sense_order, s.definition_en, s.explanation_vi,
                       s.cefr_level, s.topic, s.register
                from vocabulary_senses s join part_of_speech p on p.id = s.part_of_speech_id
                where s.vocabulary_id = ? order by s.sense_order
                """, (rs, row) -> {
                    UUID senseId = rs.getObject("id", UUID.class);
                    List<VocabularyDetailResponse.Example> examples = jdbc.query("""
                            select id, sentence_en, translation_vi, display_order
                            from vocabulary_examples where vocabulary_sense_id = ? order by display_order
                            """, (exampleRs, exampleRow) -> new VocabularyDetailResponse.Example(
                            exampleRs.getObject("id", UUID.class), exampleRs.getString("sentence_en"),
                            exampleRs.getString("translation_vi"), exampleRs.getInt("display_order")), senseId);
                    return new VocabularyDetailResponse.Sense(senseId, rs.getString("part_of_speech"),
                            rs.getInt("sense_order"), rs.getString("definition_en"), rs.getString("explanation_vi"),
                            rs.getString("cefr_level"), rs.getString("topic"), rs.getString("register"), examples);
                }, id);

        List<VocabularyDetailResponse.Pronunciation> pronunciations = jdbc.query("""
                select id, vocabulary_sense_id, accent, ipa, stress_pattern, audio_path, source_type
                from vocabulary_pronunciations
                where vocabulary_id = ? or vocabulary_sense_id in
                    (select id from vocabulary_senses where vocabulary_id = ?)
                order by accent
                """, (rs, row) -> pronunciation(rs), id, id);
        return new VocabularyDetailResponse(word.id(), word.language(), word.lemma(), senses, pronunciations);
    }

    @Transactional
    public void updateLemma(UUID id, String lemma, UUID userId, boolean admin) {
        int updated = jdbc.update("""
                update vocabularies set lemma = ?, normalized_lemma = ?, updated_at = now()
                where id = ? and (? = true or ((source_type in ('USER', 'USER_IMPORT')) and created_by = ?))
                """, lemma.trim(), lemma.trim().toLowerCase(java.util.Locale.ROOT), id, admin, userId);
        if (updated == 0) {
            boolean exists = jdbc.query("select 1 from vocabularies where id = ?",
                    (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) java.sql.ResultSet::next, id);
            if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "Vocabulary not found");
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot edit this vocabulary");
        }
    }

    @Transactional
    public UUID savePronunciation(UUID vocabularyId, UUID senseId, String accent, String ipa,
            String stressPattern, String audioPath, String contentType, UUID userId, boolean admin) {
        assertEditableVocabulary(vocabularyId, userId, admin);
        if (senseId != null) {
            Boolean belongs = jdbc.query("select 1 from vocabulary_senses where id = ? and vocabulary_id = ?",
                    (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) java.sql.ResultSet::next, senseId, vocabularyId);
            if (!belongs) throw new ApiException(HttpStatus.BAD_REQUEST, "Sense does not belong to this vocabulary");
            List<UUID> existing = jdbc.query("select id from vocabulary_pronunciations where vocabulary_sense_id = ? and accent = ?",
                    (rs, row) -> rs.getObject(1, UUID.class), senseId, accent);
            if (!existing.isEmpty()) {
                jdbc.update("update vocabulary_pronunciations set ipa = coalesce(?, ipa), stress_pattern = coalesce(?, stress_pattern), audio_path = coalesce(?, audio_path), content_type = coalesce(?, content_type), source_type = 'USER' where id = ?",
                        blankToNull(ipa), blankToNull(stressPattern), audioPath, contentType, existing.getFirst());
                return existing.getFirst();
            }
            return jdbc.queryForObject("""
                    insert into vocabulary_pronunciations
                        (vocabulary_sense_id, accent, ipa, stress_pattern, audio_path, content_type, source_type)
                    values (?, ?, ?, ?, ?, ?, 'USER') returning id
                    """, UUID.class, senseId, accent, blankToNull(ipa), blankToNull(stressPattern), audioPath, contentType);
        }
        List<UUID> existing = jdbc.query("select id from vocabulary_pronunciations where vocabulary_id = ? and accent = ?",
                (rs, row) -> rs.getObject(1, UUID.class), vocabularyId, accent);
        if (!existing.isEmpty()) {
            jdbc.update("update vocabulary_pronunciations set ipa = coalesce(?, ipa), stress_pattern = coalesce(?, stress_pattern), audio_path = coalesce(?, audio_path), content_type = coalesce(?, content_type), source_type = 'USER' where id = ?",
                    blankToNull(ipa), blankToNull(stressPattern), audioPath, contentType, existing.getFirst());
            return existing.getFirst();
        }
        return jdbc.queryForObject("""
                insert into vocabulary_pronunciations
                    (vocabulary_id, accent, ipa, stress_pattern, audio_path, content_type, source_type)
                values (?, ?, ?, ?, ?, ?, 'USER') returning id
                """, UUID.class, vocabularyId, accent, blankToNull(ipa), blankToNull(stressPattern), audioPath, contentType);
    }

    public record StoredPronunciation(UUID id, String audioPath, String contentType) {}

    public StoredPronunciation pronunciationAudio(UUID id) {
        return jdbc.query("select id, audio_path, content_type from vocabulary_pronunciations where id = ?",
                rs -> rs.next() ? new StoredPronunciation(rs.getObject("id", UUID.class), rs.getString("audio_path"), rs.getString("content_type")) : null,
                id);
    }

    private void assertEditableVocabulary(UUID vocabularyId, UUID userId, boolean admin) {
        List<Boolean> allowed = jdbc.query("""
                select ? or ((source_type in ('USER', 'USER_IMPORT')) and created_by = ?)
                from vocabularies where id = ?
                """, (rs, row) -> rs.getBoolean(1), admin, userId, vocabularyId);
        if (allowed.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Vocabulary not found");
        if (!allowed.getFirst()) throw new ApiException(HttpStatus.FORBIDDEN, "You cannot edit pronunciation for this vocabulary");
    }

    private static VocabularyDetailResponse.Pronunciation pronunciation(ResultSet rs) throws SQLException {
        String path = rs.getString("audio_path");
        UUID id = rs.getObject("id", UUID.class);
        return new VocabularyDetailResponse.Pronunciation(id,
                rs.getObject("vocabulary_sense_id", UUID.class), rs.getString("accent"), rs.getString("ipa"),
                rs.getString("stress_pattern"), path != null,
                path == null ? null : "/api/v1/pronunciations/" + id + "/audio", rs.getString("source_type"));
    }

    private static final RowMapper<VocabularySummaryResponse> SUMMARY_MAPPER = (rs, row) ->
            new VocabularySummaryResponse(rs.getObject("id", UUID.class), rs.getString("language"),
                    rs.getString("lemma"), rs.getTimestamp("created_at").toInstant());

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void provenance(UUID vocabularyId, UUID senseId, String field, String sourceType, UUID userId) {
        jdbc.update("""
                insert into lexical_field_provenance (vocabulary_id, vocabulary_sense_id, field_name, source_type, created_by)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """, vocabularyId, senseId, field, sourceType, userId);
    }
}
