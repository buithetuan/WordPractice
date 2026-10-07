package com.vocablab.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vocablab.exception.ApiException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class EnrichmentProviderClient {
    private final RestClient restClient;

    public EnrichmentProviderClient(@Value("${spring.ai-service.url:http://localhost:8000}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ProviderResponse suggest(ProviderRequest request) {
        try {
            ProviderResponse response = restClient.post().uri("/v1/enrichment/suggestions")
                    .body(request).retrieve().body(ProviderResponse.class);
            if (response == null || response.suggestions() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI provider returned an empty response");
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Enrichment provider is unavailable");
        }
    }

    public record ProviderRequest(
            String lemma,
            @JsonProperty("part_of_speech") String partOfSpeech,
            @JsonProperty("definition_en") String definitionEn,
            @JsonProperty("explanation_vi") String explanationVi,
            @JsonProperty("existing_senses") List<ExistingSense> existingSenses) {}

    public record ExistingSense(
            @JsonProperty("part_of_speech") String partOfSpeech,
            @JsonProperty("definition_en") String definitionEn,
            @JsonProperty("explanation_vi") String explanationVi) {}

    public record ProviderResponse(String provider, String model, boolean ambiguous, List<Suggestion> suggestions) {}

    public record Suggestion(
            @JsonProperty("part_of_speech") String partOfSpeech,
            @JsonProperty("definition_en") String definitionEn,
            @JsonProperty("explanation_vi") String explanationVi,
            @JsonProperty("cefr_level") String cefrLevel,
            String topic,
            List<String> examples,
            @JsonProperty("related_words") List<String> relatedWords) {}
}
