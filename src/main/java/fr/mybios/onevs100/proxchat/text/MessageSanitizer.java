package fr.mybios.onevs100.proxchat.text;

import java.text.Normalizer;

/**
 * Turns untrusted chat input into a plain, single-line, bounded string — or a verdict that no
 * bubble may render. Pure static logic, no Bukkit types (unit-fuzzable).
 *
 * Pipeline: NFC-normalize → map every whitespace-class code point (including newlines: client
 * lineWidth wrapping is the only line-break authority) to a plain space → drop ISO controls,
 * the invisible/bidi set (U+200B–200F, U+202A–202E, U+2060–2064, U+FEFF, U+00AD) and U+00A7 `§`
 * (legacy formatting-code lead-in — some client render paths honor §-codes even in raw display
 * text) → collapse space runs and trim → count CODE POINTS against the cap. Over-cap input is
 * REJECTED, never truncated (deliberate: silent truncation misquotes the speaker).
 *
 * The OK text is only ever rendered via {@code Component.text(literal)} — never deserialized as
 * MiniMessage/legacy markup (the known abuse vector). French accents pass untouched (NFC; the
 * build pins UTF-8). Stripping U+200D (ZWJ) intentionally degrades composite emoji into their
 * components: a zero-width joiner is also a steganography channel, so it does not survive.
 */
public final class MessageSanitizer {

    private MessageSanitizer() {
    }

    public static SanitizeResult sanitize(String raw, int maxCodePoints) {
        if (raw == null || raw.isEmpty()) {
            return SanitizeResult.empty();
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(nfc.length());
        boolean pendingSpace = false;
        int i = 0;
        while (i < nfc.length()) {
            int cp = nfc.codePointAt(i);
            i += Character.charCount(cp);
            // Whitespace first: \t\n\r\f are ISO controls too, but they separate words, so they
            // must become spaces rather than vanish ("line1\nline2" must not read "line1line2").
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0; // leading whitespace dies here, runs collapse
                continue;
            }
            if (Character.isISOControl(cp) || isInvisible(cp)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        if (out.isEmpty()) {
            return SanitizeResult.empty();
        }
        String text = out.toString();
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            return SanitizeResult.tooLong();
        }
        return SanitizeResult.ok(text);
    }

    private static boolean isInvisible(int cp) {
        return (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x2064)
                || cp == 0xFEFF
                || cp == 0x00AD
                || cp == 0x00A7; // § — client-honored formatting lead-in, never renderable content
    }
}
