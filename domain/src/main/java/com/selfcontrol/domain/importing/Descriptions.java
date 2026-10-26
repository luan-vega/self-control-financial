package com.selfcontrol.domain.importing;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normaliza descrições e nomes para comparar: sem acentos, minúsculas, espaços colapsados. */
public final class Descriptions {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Descriptions() {
    }

    /** "  Padaria   São João " e "PADARIA SAO JOAO" viram a mesma chave. */
    public static String key(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        String plain = MARKS.matcher(decomposed).replaceAll("");
        return SPACES.matcher(plain.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
