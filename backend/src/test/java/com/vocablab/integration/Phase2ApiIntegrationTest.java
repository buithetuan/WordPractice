package com.vocablab.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocablab.service.impl.EnrichmentProviderClient;
import com.vocablab.service.impl.EnrichmentProviderClient.ProviderResponse;
import com.vocablab.service.impl.EnrichmentProviderClient.Suggestion;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class Phase2ApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private EnrichmentProviderClient enrichmentProvider;

    @Test
    void vocabularySupportsMultipleSensesAndPartsOfSpeechThroughDtos() throws Exception {
        String request = """
                {"language":"en","lemma":"codex-phase2-bank","senses":[
                  {"partOfSpeech":"NOUN","definitionEn":"A financial institution","explanationVi":"ngân hàng"},
                  {"partOfSpeech":"NOUN","definitionEn":"Land beside a river","explanationVi":"bờ sông"},
                  {"partOfSpeech":"VERB","definitionEn":"To rely on","explanationVi":"dựa vào"}
                ]}
                """;
        String response = mvc.perform(post("/api/v1/vocabularies").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.senses.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(response);
        UUID id = UUID.fromString(created.path("id").asText());
        mvc.perform(get("/api/v1/vocabularies/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lemma").value("codex-phase2-bank"))
                .andExpect(jsonPath("$.senses[0].partOfSpeech").value("NOUN"))
                .andExpect(jsonPath("$.senses[2].partOfSpeech").value("VERB"));
    }

    @Test
    void sharedSetItemsCanBeExcludedButNotRemovedByOrdinaryUser() throws Exception {
        String wordResponse = mvc.perform(post("/api/v1/vocabularies").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"language":"en","lemma":"codex-phase2-shared-word","senses":[{"partOfSpeech":"NOUN","definitionEn":"A shared test word"}]}
                        """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode word = objectMapper.readTree(wordResponse);
        UUID userId = jdbc.queryForObject("select created_by from vocabularies where id = ?", UUID.class,
                UUID.fromString(word.path("id").asText()));
        UUID setId = jdbc.queryForObject("""
                insert into vocabulary_sets (name, owner_user_id, visibility, source_type)
                values ('codex-phase2-shared', null, 'PLATFORM', 'PLATFORM') returning id
                """, UUID.class);
        UUID itemId = jdbc.queryForObject("""
                insert into vocabulary_set_items (vocabulary_set_id, vocabulary_id, vocabulary_sense_id)
                values (?, ?, ?) returning id
                """, UUID.class, setId, UUID.fromString(word.path("id").asText()), UUID.fromString(word.path("senses").get(0).path("id").asText()));

        mvc.perform(delete("/api/v1/vocabulary-sets/items/{itemId}", itemId)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/vocabulary-sets/items/{itemId}/exclude", itemId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/vocabulary-sets/{setId}/items", setId)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        Integer activeRows = jdbc.queryForObject("select count(*) from vocabulary_set_items where id = ? and removed_at is null", Integer.class, itemId);
        org.junit.jupiter.api.Assertions.assertEquals(1, activeRows);
        org.junit.jupiter.api.Assertions.assertNotNull(userId);
    }

    @Test
    void pronunciationMetadataCanBeSavedAndReturned() throws Exception {
        String wordResponse = mvc.perform(post("/api/v1/vocabularies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"lemma\":\"codex-phase2-pronunciation\",\"senses\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID vocabularyId = UUID.fromString(objectMapper.readTree(wordResponse).path("id").asText());

        mvc.perform(post("/api/v1/vocabularies/{id}/pronunciations", vocabularyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accent\":\"UK\",\"ipa\":\"/wɜːd/\",\"stressPattern\":\"1\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/vocabularies/{id}", vocabularyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronunciations.length()").value(1))
                .andExpect(jsonPath("$.pronunciations[0].accent").value("UK"))
                .andExpect(jsonPath("$.pronunciations[0].ipa").value("/wɜːd/"));
    }

    @Test
    void csvImportPreviewsThenImportsValidRowsAndReportsAmbiguousRows() throws Exception {
        String csv = "Từ loại,Từ vựng,Nghĩa tiếng Việt\r\nnoun,codex-phase2-apple,quả táo\r\n,codex-phase2-bank,ngân hàng\r\n";
        MockMultipartFile file = new MockMultipartFile("file", "phase2.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        String previewJson = mvc.perform(multipart("/api/v1/imports/preview").file(file))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.columns[0].field").value("POS"))
                .andExpect(jsonPath("$.columns[1].field").value("WORD"))
                .andExpect(jsonPath("$.validCount").value(1)).andExpect(jsonPath("$.ambiguousCount").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(objectMapper.readTree(previewJson).path("jobId").asText());

        mvc.perform(post("/api/v1/imports/{jobId}/confirm", jobId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1)).andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"));
        Integer importedVocabulary = jdbc.queryForObject("select count(*) from vocabularies where normalized_lemma = 'codex-phase2-apple'", Integer.class);
        Integer rejectedVocabulary = jdbc.queryForObject("select count(*) from vocabularies where normalized_lemma = 'codex-phase2-bank'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, importedVocabulary);
        org.junit.jupiter.api.Assertions.assertEquals(0, rejectedVocabulary);
    }

    @Test
    void enrichmentMustBeExplicitlyAcceptedAndPersistsSenseFieldProvenance() throws Exception {
        when(enrichmentProvider.suggest(any())).thenReturn(new ProviderResponse("MOCK", "fixture-v1", false,
                java.util.List.of(new Suggestion("ADJECTIVE", "Able to continue without lasting harm.",
                        "có thể duy trì lâu dài", "B2", "environment",
                        java.util.List.of("Sustainable energy reduces emissions."), java.util.List.of("renewable")))));
        String wordResponse = mvc.perform(post("/api/v1/vocabularies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"lemma\":\"codex-phase2-sustainable\",\"senses\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID vocabularyId = UUID.fromString(objectMapper.readTree(wordResponse).path("id").asText());
        String suggestionResponse = mvc.perform(post("/api/v1/enrichment/suggestions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vocabularyId\":\"" + vocabularyId + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ambiguous").value(false))
                .andExpect(jsonPath("$.provider").value("MOCK"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(objectMapper.readTree(suggestionResponse).path("jobId").asText());

        String applied = mvc.perform(post("/api/v1/enrichment/{jobId}/apply", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedFields\":[\"DEFINITION_EN\",\"EXPLANATION_VI\",\"CEFR_LEVEL\",\"TOPIC\",\"EXAMPLES\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID senseId = UUID.fromString(objectMapper.readTree(applied).path("vocabularySenseId").asText());
        mvc.perform(get("/api/v1/vocabularies/{id}", vocabularyId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.senses.length()").value(1))
                .andExpect(jsonPath("$.senses[0].partOfSpeech").value("ADJECTIVE"))
                .andExpect(jsonPath("$.senses[0].cefrLevel").value("B2"))
                .andExpect(jsonPath("$.senses[0].examples.length()").value(1));
        Integer provenanceRows = jdbc.queryForObject("select count(*) from lexical_field_provenance where vocabulary_sense_id = ? and source_type = 'AI'", Integer.class, senseId);
        org.junit.jupiter.api.Assertions.assertEquals(5, provenanceRows);
    }

    @Test
    void ambiguousAiSuggestionRequiresAnExplicitSenseChoice() throws Exception {
        when(enrichmentProvider.suggest(any())).thenReturn(new ProviderResponse("MOCK", "fixture-v1", true,
                java.util.List.of(new Suggestion("NOUN", "A financial institution.", "ngân hàng", null, null, java.util.List.of(), java.util.List.of()),
                        new Suggestion("NOUN", "Land beside a river.", "bờ sông", null, null, java.util.List.of(), java.util.List.of()),
                        new Suggestion("VERB", "To rely on.", "dựa vào", null, null, java.util.List.of(), java.util.List.of()))));
        String wordResponse = mvc.perform(post("/api/v1/vocabularies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en\",\"lemma\":\"codex-phase2-ambiguous\",\"senses\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID vocabularyId = UUID.fromString(objectMapper.readTree(wordResponse).path("id").asText());
        String suggestionResponse = mvc.perform(post("/api/v1/enrichment/suggestions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vocabularyId\":\"" + vocabularyId + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ambiguous").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(objectMapper.readTree(suggestionResponse).path("jobId").asText());

        mvc.perform(post("/api/v1/enrichment/{jobId}/apply", jobId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedFields\":[\"DEFINITION_EN\",\"EXPLANATION_VI\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/enrichment/{jobId}/apply", jobId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedFields\":[\"DEFINITION_EN\",\"EXPLANATION_VI\"],\"selectedSuggestionIndex\":1}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/vocabularies/{id}", vocabularyId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.senses.length()").value(1))
                .andExpect(jsonPath("$.senses[0].definitionEn").value("Land beside a river."));
    }
}
