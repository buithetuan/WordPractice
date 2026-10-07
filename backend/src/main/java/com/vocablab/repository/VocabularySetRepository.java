package com.vocablab.repository;

import com.vocablab.dto.response.VocabularySetItemResponse;
import com.vocablab.dto.response.VocabularySetResponse;
import com.vocablab.exception.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VocabularySetRepository {
    private final JdbcTemplate jdbc;

    public VocabularySetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VocabularySetResponse> list(UUID userId) {
        return jdbc.query("""
                select s.id, s.name, s.description, s.visibility,
                       (select count(*) from vocabulary_set_items i
                        where i.vocabulary_set_id = s.id and i.removed_at is null
                          and not exists (select 1 from user_excluded_set_items x
                                          where x.vocabulary_set_item_id = i.id and x.user_id = ? and x.restored_at is null)) as item_count,
                       s.created_at
                from vocabulary_sets s
                where s.deleted_at is null
                  and (s.owner_user_id = ? or s.visibility in ('SHARED', 'PUBLIC', 'PLATFORM'))
                order by s.updated_at desc
                """, (rs, row) -> new VocabularySetResponse(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getString("visibility"),
                rs.getInt("item_count"), rs.getTimestamp("created_at").toInstant()), userId, userId);
    }

    @Transactional
    public UUID create(String name, String description, UUID userId) {
        return jdbc.queryForObject("""
                insert into vocabulary_sets (name, description, owner_user_id, visibility, source_type, created_by, updated_by)
                values (?, ?, ?, 'PRIVATE', 'USER', ?, ?)
                returning id
                """, UUID.class, name.trim(), blankToNull(description), userId, userId, userId);
    }

    @Transactional
    public void update(UUID setId, String name, String description, UUID userId, boolean admin) {
        requireCanMutate(setId, userId, admin);
        jdbc.update("""
                update vocabulary_sets set name = ?, description = ?, updated_at = now(), updated_by = ?
                where id = ? and deleted_at is null
                """, name.trim(), blankToNull(description), userId, setId);
    }

    public List<VocabularySetItemResponse> items(UUID setId, UUID userId) {
        requireVisible(setId, userId);
        return jdbc.query("""
                select i.id, i.vocabulary_id, i.vocabulary_sense_id, v.lemma,
                       s.definition_en, i.added_at
                from vocabulary_set_items i
                join vocabularies v on v.id = i.vocabulary_id
                left join vocabulary_senses s on s.id = i.vocabulary_sense_id
                where i.vocabulary_set_id = ? and i.removed_at is null
                  and not exists (select 1 from user_excluded_set_items x
                                  where x.vocabulary_set_item_id = i.id and x.user_id = ? and x.restored_at is null)
                order by i.added_at, i.id
                """, (rs, row) -> new VocabularySetItemResponse(rs.getObject("id", UUID.class),
                rs.getObject("vocabulary_id", UUID.class), rs.getObject("vocabulary_sense_id", UUID.class),
                rs.getString("lemma"), rs.getString("definition_en"), rs.getTimestamp("added_at").toInstant()), setId, userId);
    }

    @Transactional
    public UUID addItem(UUID setId, UUID vocabularyId, UUID senseId, UUID userId, boolean admin) {
        requireCanMutate(setId, userId, admin);
        List<UUID> duplicates = jdbc.query("""
                select id from vocabulary_set_items
                where vocabulary_set_id = ? and vocabulary_id = ? and vocabulary_sense_id is not distinct from ? and removed_at is null
                """, (rs, row) -> rs.getObject(1, UUID.class), setId, vocabularyId, senseId);
        if (!duplicates.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "Item already exists in this set");
        return jdbc.queryForObject("""
                insert into vocabulary_set_items (vocabulary_set_id, vocabulary_id, vocabulary_sense_id, added_by)
                values (?, ?, ?, ?) returning id
                """, UUID.class, setId, vocabularyId, senseId, userId);
    }

    @Transactional
    public void removeItem(UUID itemId, UUID userId, boolean admin) {
        UUID setId = jdbc.query("select vocabulary_set_id from vocabulary_set_items where id = ? and removed_at is null",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, itemId);
        if (setId == null) throw new ApiException(HttpStatus.NOT_FOUND, "Active set item not found");
        requireCanMutate(setId, userId, admin);
        jdbc.update("update vocabulary_set_items set removed_by = ?, removed_at = now() where id = ? and removed_at is null", userId, itemId);
    }

    @Transactional
    public void deleteSet(UUID setId, UUID userId, boolean admin) {
        requireCanMutate(setId, userId, admin);
        jdbc.update("update vocabulary_sets set deleted_at = now(), updated_at = now(), updated_by = ? where id = ? and deleted_at is null", userId, setId);
    }

    @Transactional
    public void excludeItem(UUID itemId, UUID userId) {
        String visibility = jdbc.query("""
                select s.visibility from vocabulary_set_items i join vocabulary_sets s on s.id = i.vocabulary_set_id
                where i.id = ? and i.removed_at is null and s.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, itemId);
        if (visibility == null) throw new ApiException(HttpStatus.NOT_FOUND, "Active set item not found");
        if (visibility.equals("PRIVATE")) throw new ApiException(HttpStatus.BAD_REQUEST, "Private set items should be removed from the set");
        jdbc.update("""
                insert into user_excluded_set_items (user_id, vocabulary_set_item_id)
                values (?, ?)
                on conflict (user_id, vocabulary_set_item_id)
                do update set excluded_at = now(), restored_at = null
                """, userId, itemId);
    }

    @Transactional
    public void restoreItem(UUID itemId, UUID userId) {
        int updated = jdbc.update("""
                update user_excluded_set_items set restored_at = now()
                where user_id = ? and vocabulary_set_item_id = ? and restored_at is null
                """, userId, itemId);
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Active personal exclusion not found");
    }

    private void requireCanMutate(UUID setId, UUID userId, boolean admin) {
        String visibility = jdbc.query("select visibility from vocabulary_sets where id = ? and deleted_at is null",
                rs -> rs.next() ? rs.getString(1) : null, setId);
        if (visibility == null) throw new ApiException(HttpStatus.NOT_FOUND, "Vocabulary set not found");
        UUID ownerId = jdbc.query("select owner_user_id from vocabulary_sets where id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, setId);
        if (!admin && (ownerId == null || !ownerId.equals(userId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the set owner or an administrator may change this set");
        }
    }

    private void requireVisible(UUID setId, UUID userId) {
        String visibility = jdbc.query("select visibility from vocabulary_sets where id = ? and deleted_at is null",
                rs -> rs.next() ? rs.getString(1) : null, setId);
        if (visibility == null) throw new ApiException(HttpStatus.NOT_FOUND, "Vocabulary set not found");
        if (visibility.equals("PRIVATE")) {
            UUID ownerId = jdbc.query("select owner_user_id from vocabulary_sets where id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, setId);
            if (!userId.equals(ownerId)) throw new ApiException(HttpStatus.NOT_FOUND, "Vocabulary set not found");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
