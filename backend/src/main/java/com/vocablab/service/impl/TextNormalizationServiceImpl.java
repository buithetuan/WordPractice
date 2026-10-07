package com.vocablab.service.impl;

import com.vocablab.service.TextNormalizationService;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TextNormalizationServiceImpl implements TextNormalizationService {
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    @Override
    public NormalizedText normalizeForReview(String value) {
        String normalized = nfc(value == null ? "" : value.replace('\u00a0', ' ').replace("\ufeff", ""));
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        if (!looksMojibake(normalized)) {
            return new NormalizedText(normalized, false, null, 1.0);
        }
        String repaired = tryDecodeLatin1AsUtf8(normalized);
        if (repaired != null && isPlausibleVietnamese(repaired) && !isPlausibleVietnamese(normalized)) {
            return new NormalizedText(normalized, true, nfc(repaired), 0.95);
        }
        return new NormalizedText(normalized, true, null, 0.35);
    }

    @Override
    public String normalizeLemma(String value) {
        return WHITESPACE.matcher(nfc(value == null ? "" : value.replace('\u00a0', ' ')).trim())
                .replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    @Override
    public String normalizedHeader(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        String ascii = decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(ascii.replace('_', ' ').replace('-', ' ').trim()).replaceAll(" ");
    }

    private static String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static boolean looksMojibake(String value) {
        return value.contains("Ã") || value.contains("Â") || value.contains("áº") || value.contains("á»");
    }

    private static String tryDecodeLatin1AsUtf8(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static boolean isPlausibleVietnamese(String value) {
        return value.codePoints().anyMatch(codePoint -> (codePoint >= 0x1e00 && codePoint <= 0x1eff)
                || codePoint == 'đ' || codePoint == 'Đ');
    }
}
