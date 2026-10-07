package com.vocablab.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocablab.exception.ApiException;
import com.vocablab.service.impl.EnrichmentProviderClient.ProviderResponse;
import com.vocablab.service.impl.EnrichmentProviderClient.Suggestion;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EnrichmentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EnrichmentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID saveSuggestion(UUID userId, UUID vocabularyId, UUID senseId, String input,
            ProviderResponse response) {
        try {
            return jdbc.queryForObject("""
                    insert into enrichment_jobs
                        (owner_user_id, vocabulary_id, vocabulary_sense_id, provider, model,
                         status, input_snapshot, output_payload)
                    values (?, ?, ?, ?, ?, 'SUGGESTED', ?::jsonb, ?::jsonb)
                    returning id
                    """, UUID.class, userId, vocabularyId, senseId, response.provider(), response.model(),
                    input, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store enrichment response");
        }
    }

    @Transactional
    public UUID apply(UUID jobId, UUID userId, List<String> acceptedFields, Integer selectedIndex) {
        EnrichmentJob job = jdbc.query("""
                select vocabulary_id, vocabulary_sense_id, output_payload::text, status
                from enrichment_jobs where id = ? and owner_user_id = ?
                """, rs -> rs.next() ? new EnrichmentJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4)) : null, jobId, userId);
        if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "Enrichment suggestion not found");
        if (!job.status.equals("SUGGESTED")) throw new ApiException(HttpStatus.CONFLICT, "Suggestion is no longer pending");
        ProviderResponse response;
        try {
            response = objectMapper.readValue(job.outputJson, ProviderResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored enrichment response is invalid");
        }
        if (response.ambiguous() && selectedIndex == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a sense suggestion before applying ambiguous enrichment");
        }
        int index = selectedIndex == null ? 0 : selectedIndex;
        if (index < 0 || index >= response.suggestions().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Suggestion index is out of range");
        }
        for (String field : acceptedFields) {
            if (!List.of("DEFINITION_EN", "EXPLANATION_VI", "CEFR_LEVEL", "TOPIC", "EXAMPLES", "RELATED_WORDS").contains(field)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported enrichment field: " + field);
            }
        }
        Suggestion suggestion = response.suggestions().get(index);
        UUID senseId = job.senseId == null ? resolveSense(job.vocabularyId, suggestion, userId, acceptedFields) : job.senseId;
        if (acceptedFields.contains("DEFINITION_EN")) {
            updateSenseField(senseId, "definition_en", suggestion.definitionEn());
            provenance(senseId, "DEFINITION_EN", response);
        }
        if (acceptedFields.contains("EXPLANATION_VI")) {
            updateSenseField(senseId, "explanation_vi", suggestion.explanationVi());
            provenance(senseId, "EXPLANATION_VI", response);
        }
        if (acceptedFields.contains("CEFR_LEVEL")) {
            String cefr = suggestion.cefrLevel();
            if (cefr != null && !List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(cefr)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned an invalid CEFR level");
            }
            updateSenseField(senseId, "cefr_level", cefr);
            provenance(senseId, "CEFR_LEVEL", response);
        }
        if (acceptedFields.contains("TOPIC")) {
            updateSenseField(senseId, "topic", suggestion.topic());
            provenance(senseId, "TOPIC", response);
        }
        if (acceptedFields.contains("EXAMPLES")) {
            int displayOrder = jdbc.queryForObject("select coalesce(max(display_order), 0) + 1 from vocabulary_examples where vocabulary_sense_id = ?", Integer.class, senseId);
            for (String example : suggestion.examples() == null ? List.<String>of() : suggestion.examples()) {
                Boolean exists = jdbc.query("select 1 from vocabulary_examples where vocabulary_sense_id = ? and sentence_en = ?",
                        (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) java.sql.ResultSet::next, senseId, example);
                if (!exists) jdbc.update("insert into vocabulary_examples (vocabulary_sense_id, sentence_en, source_type, display_order) values (?, ?, 'AI', ?)", senseId, example, displayOrder++);
            }
            provenance(senseId, "EXAMPLES", response);
        }
        if (acceptedFields.contains("RELATED_WORDS")) {
            saveRelatedWords(job.vocabularyId, suggestion.relatedWords(), userId);
            provenance(senseId, "RELATED_WORDS", response);
        }
        jdbc.update("update enrichment_jobs set status = 'APPLIED', applied_at = now() where id = ?", jobId);
        return senseId;
    }

    @Transactional
    public void reject(UUID jobId, UUID userId) {
        int changed = jdbc.update("update enrichment_jobs set status = 'REJECTED' where id = ? and owner_user_id = ? and status = 'SUGGESTED'", jobId, userId);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Pending enrichment suggestion not found");
    }

    private UUID resolveSense(UUID vocabularyId, Suggestion suggestion, UUID userId, List<String> acceptedFields) {
        if (suggestion.partOfSpeech() == null || suggestion.partOfSpeech().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider did not resolve a part of speech");
        }
        Short posId = jdbc.query("select id from part_of_speech where code = ?", rs -> rs.next() ? rs.getShort(1) : null,
                suggestion.partOfSpeech().toUpperCase());
        if (posId == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned an unknown part of speech");
        List<UUID> existing = jdbc.query("""
                select id from vocabulary_senses where vocabulary_id = ? and part_of_speech_id = ?
                  and coalesce(definition_en, '') = coalesce(?, '')
                """, (rs, row) -> rs.getObject(1, UUID.class), vocabularyId, posId, suggestion.definitionEn());
        if (!existing.isEmpty()) return existing.getFirst();
        Short order = jdbc.queryForObject("select coalesce(max(sense_order), 0) + 1 from vocabulary_senses where vocabulary_id = ?", Short.class, vocabularyId);
        return jdbc.queryForObject("""
                insert into vocabulary_senses (vocabulary_id, part_of_speech_id, sense_order, definition_en,
                    explanation_vi, cefr_level, topic, source_type, created_by)
                values (?, ?, ?, ?, ?, ?, ?, 'AI', ?) returning id
                """, UUID.class, vocabularyId, posId, order,
                acceptedFields.contains("DEFINITION_EN") ? suggestion.definitionEn() : null,
                acceptedFields.contains("EXPLANATION_VI") ? suggestion.explanationVi() : null,
                acceptedFields.contains("CEFR_LEVEL") ? suggestion.cefrLevel() : null,
                acceptedFields.contains("TOPIC") ? suggestion.topic() : null, userId);
    }

    private void updateSenseField(UUID senseId, String column, String value) {
        if (!List.of("definition_en", "explanation_vi", "cefr_level", "topic").contains(column)) {
            throw new IllegalArgumentException("Unsupported sense field");
        }
        jdbc.update("update vocabulary_senses set " + column + " = ?, updated_at = now() where id = ?", value, senseId);
    }

    private void provenance(UUID senseId, String field, ProviderResponse response) {
        jdbc.update("""
                insert into lexical_field_provenance (vocabulary_sense_id, field_name, source_type, source_reference)
                values (?, ?, 'AI', ?)
                on conflict (vocabulary_sense_id, field_name) where vocabulary_sense_id is not null
                do update set source_type = 'AI', source_reference = excluded.source_reference, created_at = now()
                """, senseId, field, response.provider() + ":" + response.model());
    }

    private void saveRelatedWords(UUID vocabularyId, List<String> relatedWords, UUID userId) {
        if (relatedWords == null) return;
        String language = jdbc.queryForObject("select language from vocabularies where id = ?", String.class, vocabularyId);
        for (String related : relatedWords) {
            String normalized = related.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isBlank()) continue;
            UUID target = jdbc.queryForObject("""
                    insert into vocabularies (language, lemma, normalized_lemma, created_by, source_type)
                    values (?, ?, ?, ?, 'AI')
                    on conflict (language, normalized_lemma) do update set updated_at = now()
                    returning id
                    """, UUID.class, language, related.trim(), normalized, userId);
            if (!target.equals(vocabularyId)) {
                jdbc.update("""
                        insert into vocabulary_related_words (vocabulary_id, related_vocabulary_id, relation_type, source_type)
                        values (?, ?, 'TOPIC_RELATED', 'AI') on conflict do nothing
                        """, vocabularyId, target);
            }
        }
    }

    private record EnrichmentJob(UUID vocabularyId, UUID senseId, String outputJson, String status) {}
}
