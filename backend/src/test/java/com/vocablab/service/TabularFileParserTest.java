package com.vocablab.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TabularFileParserTest {
    private final TabularFileParser parser = new TabularFileParser();

    @Test
    void parsesQuotedCsvFieldsAndVietnameseUtf8() throws Exception {
        byte[] csv = "Word,Meaning,POS\r\nbank,\"bờ sông, ven nước\",noun\r\n".getBytes(StandardCharsets.UTF_8);
        var result = parser.parse(csv, "words.csv");
        assertEquals("UTF-8", result.encoding());
        assertEquals("bank", result.rows().getFirst().getFirst());
        assertEquals("bờ sông, ven nước", result.rows().getFirst().get(1));
        assertFalse(result.encodingRepairProposed());
    }
}
