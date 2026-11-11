package org.reloq.tts;

/**
 * Preserve EVDroid's dedicated symbol-word handling.
 *
 * The @ character is always spoken as "at", including when punctuation
 * processing is disabled. It must be expanded before punctuation processing
 * so the generic SAPI5 symbol table cannot turn it into "at sign".
 */
final class SymbolWords {
    static String apply(String text) {
        if (text.isEmpty() || text.indexOf('@') < 0) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '@') {
                pad(out);
                out.append("at ");
            } else {
                out.append(c);
            }
        }
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    private static void pad(StringBuilder out) {
        if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.append(' ');
        }
    }

    private SymbolWords() {}
}
