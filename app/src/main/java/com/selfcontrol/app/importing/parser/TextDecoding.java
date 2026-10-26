package com.selfcontrol.app.importing.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Bancos brasileiros ainda exportam muito em Windows-1252 (OFX com CHARSET:1252, CSV do Excel).
 * Tenta UTF-8 estrito e, se os bytes não forem UTF-8 válido, lê como Windows-1252.
 */
public final class TextDecoding {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TextDecoding() {
    }

    public static String decode(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf
                ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, start, bytes.length - start, WINDOWS_1252);
        }
    }
}
