package com.vocablab.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocablab.exception.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ImportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ImportRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void create(UUID jobId, UUID ownerId, UUID setId, String filename, String fileHash,
            String encoding, boolean repairProposed, Map<String, Object> mapping, List<NewRow> rows) {
        jdbc.update("""
                insert into import_jobs (id, owner_user_id, vocabulary_set_id, original_filename,
                    file_sha256, detected_encoding, encoding_repair_proposed, column_mapping, row_count)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, jobId, ownerId, setId, filename, fileHash, encoding, repairProposed,
                json(mapping), rows.size());
        for (NewRow row : rows) {
            jdbc.update("""
                    insert into import_job_rows (import_job_id, row_number, raw_data, normalized_data, status, issues)
                    values (?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb)
                    """, jobId, row.rowNumber, json(row.raw), json(row.normalized), row.status, json(row.issues));
        }
    }

    public StoredJob get(UUID jobId, UUID ownerId) {
        return jdbc.query("""
                select id, vocabulary_set_id, original_filename, status, detected_encoding, encoding_repair_proposed
                from import_jobs where id = ? and owner_user_id = ?
                """, rs -> {
            if (!rs.next()) return null;
            return new StoredJob(rs.getObject("id", UUID.class), rs.getObject("vocabulary_set_id", UUID.class),
                    rs.getString("original_filename"), rs.getString("status"), rs.getString("detected_encoding"),
                    rs.getBoolean("encoding_repair_proposed"));
        }, jobId, ownerId);
    }

    public List<StoredRow> rows(UUID jobId) {
        return jdbc.query("""
                select row_number, raw_data::text, normalized_data::text, status, issues::text
                from import_job_rows where import_job_id = ? order by row_number
                """, (rs, row) -> new StoredRow(rs.getInt(1), readTree(rs.getString(2)),
                readTree(rs.getString(3)), rs.getString(4), readTree(rs.getString(5))), jobId);
    }

    public MappingSnapshot mapping(UUID jobId, UUID ownerId) {
        String json = jdbc.query("select column_mapping::text from import_jobs where id = ? and owner_user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, jobId, ownerId);
        if (json == null) throw new ApiException(HttpStatus.NOT_FOUND, "Import preview not found");
        JsonNode snapshot = readTree(json);
        List<String> headers = new ArrayList<>();
        JsonNode headerNode = snapshot.path("headers");
        if (headerNode.isArray()) headerNode.forEach(value -> headers.add(value.asText()));
        Map<String, Integer> fields = new java.util.LinkedHashMap<>();
        JsonNode columns = snapshot.path("columns");
        if (columns.isArray()) columns.forEach(column -> {
            String field = column.path("field").asText();
            if (!"IGNORE".equals(field) && column.has("columnIndex")) fields.putIfAbsent(field, column.path("columnIndex").asInt());
        });
        return new MappingSnapshot(fields, headers);
    }

    @Transactional
    public void updateRow(UUID jobId, int rowNumber, String status, List<String> issues,
            UUID vocabularyId, UUID senseId) {
        jdbc.update("""
                update import_job_rows set status = ?, issues = ?::jsonb, vocabulary_id = ?, vocabulary_sense_id = ?
                where import_job_id = ? and row_number = ?
                """, status, json(issues), vocabularyId, senseId, jobId, rowNumber);
    }

    @Transactional
    public void finish(UUID jobId, UUID setId, String status, int imported, int rejected) {
        jdbc.update("""
                update import_jobs set vocabulary_set_id = ?, status = ?, imported_count = ?, rejected_count = ?, confirmed_at = now()
                where id = ?
                """, setId, status, imported, rejected, jobId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize import data");
        }
    }

    private JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored import data is invalid");
        }
    }

    public record NewRow(int rowNumber, Object raw, Object normalized, String status, List<String> issues) {}
    public record StoredRow(int rowNumber, JsonNode raw, JsonNode normalized, String status, JsonNode issues) {}
    public record StoredJob(UUID id, UUID setId, String filename, String status, String encoding, boolean repairProposed) {}
    public record MappingSnapshot(Map<String, Integer> fields, List<String> headers) {}
}
