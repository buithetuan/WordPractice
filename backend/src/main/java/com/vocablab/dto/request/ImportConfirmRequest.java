package com.vocablab.dto.request;

import java.util.Map;

public record ImportConfirmRequest(
        Map<String, Integer> mapping,
        boolean acceptEncodingRepairs,
        Map<Integer, String> rowPartOfSpeechOverrides) {}
