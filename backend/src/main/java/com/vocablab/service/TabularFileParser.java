package com.vocablab.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class TabularFileParser {
    public ParsedFile parse(byte[] bytes, String filename) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Uploaded file is empty");
        String lower = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv")) return parseCsv(bytes);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return parseWorkbook(bytes);
        throw new IllegalArgumentException("Only CSV, XLS and XLSX files are supported");
    }

    private ParsedFile parseCsv(byte[] bytes) throws IOException {
        Decoded decoded = decode(bytes);
        List<List<String>> records = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(decoded.text, CSVFormat.RFC4180)) {
            for (var record : parser) {
                List<String> row = new ArrayList<>();
                record.forEach(row::add);
                records.add(row);
                if (records.size() > 5001) throw new IllegalArgumentException("Import is limited to 5,000 data rows");
            }
        }
        if (records.size() < 2) throw new IllegalArgumentException("File must contain a header and at least one data row");
        List<String> headers = records.removeFirst();
        return new ParsedFile(headers, records, decoded.encoding, decoded.encodingRepairProposed);
    }

    private ParsedFile parseWorkbook(byte[] bytes) throws IOException {
        List<List<String>> records = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("Workbook has no sheets");
            var sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            int width = 0;
            for (Row row : sheet) width = Math.max(width, row.getLastCellNum());
            if (width <= 0) throw new IllegalArgumentException("Workbook is empty");
            for (Row row : sheet) {
                List<String> values = new ArrayList<>(width);
                for (int column = 0; column < width; column++) {
                    var cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                records.add(values);
                if (records.size() > 5001) throw new IllegalArgumentException("Import is limited to 5,000 data rows");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Could not read Excel workbook", exception);
        }
        while (!records.isEmpty() && records.getFirst().stream().allMatch(String::isBlank)) records.removeFirst();
        if (records.size() < 2) throw new IllegalArgumentException("File must contain a header and at least one data row");
        List<String> headers = records.removeFirst();
        return new ParsedFile(headers, records, "XLSX", false);
    }

    private Decoded decode(byte[] bytes) throws IOException {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), "UTF-8-BOM", false);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), "UTF-16LE", false);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), "UTF-16BE", false);
        }
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new Decoded(utf8, "UTF-8", false);
        } catch (CharacterCodingException exception) {
            return new Decoded(new String(bytes, Charset.forName("windows-1252")), "WINDOWS-1252", true);
        }
    }

    public record ParsedFile(List<String> headers, List<List<String>> rows, String encoding,
            boolean encodingRepairProposed) {}

    private record Decoded(String text, String encoding, boolean encodingRepairProposed) {}
}
