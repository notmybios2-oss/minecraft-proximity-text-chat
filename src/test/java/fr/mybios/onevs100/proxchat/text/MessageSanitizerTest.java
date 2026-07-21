package fr.mybios.onevs100.proxchat.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.mybios.onevs100.proxchat.text.SanitizeResult.Verdict;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Invisible/control code points are BUILT from their numeric values here, never typed as
 * literals — a literal invisible in test source is exactly the ambush these tests exist to
 * prevent. Visible French text stays literal (the build pins UTF-8).
 */
class MessageSanitizerTest {

    private static final int CAP = 96; // production default; the cap itself is parameterized

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static final String NUL = cp(0x0000);
    private static final String BEL = cp(0x0007);
    private static final String ESC = cp(0x001B);
    private static final String DEL = cp(0x007F);
    private static final String NBSP = cp(0x00A0);
    private static final String SECTION = cp(0x00A7);
    private static final String SOFT_HYPHEN = cp(0x00AD);
    private static final String COMBINING_ACUTE = cp(0x0301);
    private static final String EN_QUAD = cp(0x2000);
    private static final String ZWSP = cp(0x200B);
    private static final String ZWJ = cp(0x200D);
    private static final String RLM = cp(0x200F);
    private static final String LRE = cp(0x202A);
    private static final String RLO = cp(0x202E);
    private static final String WJ = cp(0x2060);
    private static final String INVISIBLE_PLUS = cp(0x2064);
    private static final String IDEOGRAPHIC_SPACE = cp(0x3000);
    private static final String BOM = cp(0xFEFF);
    private static final String GRIN = cp(0x1F600);
    private static final String MAN = cp(0x1F468);
    private static final String WOMAN = cp(0x1F469);

    private static SanitizeResult run(String raw) {
        return MessageSanitizer.sanitize(raw, CAP);
    }

    // ---------------------------------------------------------------- Q4 reject boundary

    @Test
    void boundary95_96_97CodePoints() {
        assertEquals(Verdict.OK, run("a".repeat(95)).verdict());
        assertEquals(Verdict.OK, run("a".repeat(96)).verdict());
        assertEquals(Verdict.TOO_LONG, run("a".repeat(97)).verdict()); // reject, never truncate
    }

    @Test
    void boundaryCountsCodePointsNotChars() {
        // U+1F600 is one code point but two UTF-16 chars: 96 emoji = 192 chars must still pass.
        assertEquals(Verdict.OK, run(GRIN.repeat(96)).verdict());
        assertEquals(Verdict.TOO_LONG, run(GRIN.repeat(97)).verdict());
    }

    @Test
    void tooLongCarriesNoText() {
        assertEquals("", run("a".repeat(200)).text()); // rejected input must never travel
    }

    @Test
    void capAppliesAfterSanitization() {
        // 96 letters padded with strippable garbage still fit: the cap prices what RENDERS.
        String padded = ZWSP + ZWSP + "a".repeat(96) + "  ";
        assertEquals(Verdict.OK, run(padded).verdict());
        assertEquals("a".repeat(96), run(padded).text());
    }

    // ---------------------------------------------------------------- line collapsing (pc-002)

    @Test
    void multiLinePasteCollapsesToOneLine() {
        SanitizeResult r = run("line1\nline2\r\nline3\rline4");
        assertEquals(Verdict.OK, r.verdict());
        assertEquals("line1 line2 line3 line4", r.text());
        assertFalse(r.text().contains("\n"));
    }

    @Test
    void whitespaceRunsCollapseAndTrim() {
        assertEquals("a b c", run("   a \t\t b  c   ").text());
    }

    @Test
    void exoticWhitespaceBecomesPlainSpace() {
        assertEquals("a b", run("a" + NBSP + EN_QUAD + IDEOGRAPHIC_SPACE + "b").text());
    }

    // ---------------------------------------------------------------- strip sets

    @Test
    void isoControlsAreDropped() {
        assertEquals("abc", run("a" + NUL + "b" + BEL + "c").text());
        assertEquals("abc", run(ESC + "abc" + DEL).text());
    }

    @Test
    void invisibleAndBidiSetIsDropped() {
        assertEquals("abcdef",
                run("a" + ZWSP + "b" + RLO + "c" + WJ + "d" + BOM + "e" + SOFT_HYPHEN + "f").text());
        assertEquals("abc",
                run("a" + RLM + "b" + LRE + "c" + INVISIBLE_PLUS + ZWJ).text());
    }

    @Test
    void legacySectionSignIsDropped() {
        // § is the legacy formatting-code lead-in; some client render paths honor §-codes even
        // in raw display text (AUDIT-1). Only § itself is stripped — the code LETTER stays,
        // which is what the abuser typed, rendered literally and formatting-dead.
        assertEquals("kobfuscated", run(SECTION + "kobfuscated").text());
        assertEquals("alb", run("a" + SECTION + "lb").text());
        assertEquals(Verdict.EMPTY, run(SECTION + SECTION + SECTION).verdict());
    }

    @Test
    void zwjEmojiFamiliesDegradeByDesign() {
        // Stripping U+200D is intentional (steganography channel): a family becomes components.
        assertEquals(MAN + WOMAN, run(MAN + ZWJ + WOMAN).text());
    }

    // ---------------------------------------------------------------- normalization & passthrough

    @Test
    void nfcComposesCombiningMarks() {
        SanitizeResult r = run("e" + COMBINING_ACUTE + "te" + COMBINING_ACUTE); // decomposed
        assertEquals("été", r.text()); // composed output
        assertEquals(3, r.text().codePointCount(0, r.text().length()));
    }

    @Test
    void frenchAccentsPassUntouched() {
        String fr = "Ça va ? On se retrouve à la forêt, œuvre héroïque !";
        assertEquals(fr, run(fr).text());
    }

    @Test
    void plainEmojiPasses() {
        assertEquals("go " + GRIN, run("go " + GRIN).text());
    }

    // ---------------------------------------------------------------- empty verdicts

    @Test
    void nothingRenderableIsEmpty() {
        assertEquals(Verdict.EMPTY, run(null).verdict());
        assertEquals(Verdict.EMPTY, run("").verdict());
        assertEquals(Verdict.EMPTY, run("   \t\n  ").verdict());
        assertEquals(Verdict.EMPTY, run(ZWSP + RLO + BOM + " " + NBSP).verdict());
    }

    // ---------------------------------------------------------------- fuzz against invariants

    @Test
    void fuzzedGarbageNeverBreaksTheInvariants() {
        // Seeded (reproducible) garbage across the whole abuse surface: controls, bidi,
        // zero-width, astral planes, exotic whitespace. Whatever goes in, an OK verdict must
        // come out trimmed, single-line, invisible-free and within the cap.
        Random rng = new Random(42);
        int[] nasty = {0x00, 0x07, 0x0A, 0x0D, 0x1B, 0x7F, 0x85, 0x9F, 0xA0, 0xA7, 0xAD,
                0x200B, 0x200D, 0x200F, 0x202E, 0x2060, 0x2064, 0x3000, 0xFEFF,
                'a', 'Z', '9', 0xE9, 0xE7, '!', ' ', 0x1F600, 0x1F9D1, 0x10FFFD};
        for (int round = 0; round < 3000; round++) {
            StringBuilder sb = new StringBuilder();
            int len = rng.nextInt(220);
            for (int i = 0; i < len; i++) {
                sb.appendCodePoint(nasty[rng.nextInt(nasty.length)]);
            }
            SanitizeResult r = MessageSanitizer.sanitize(sb.toString(), CAP);
            switch (r.verdict()) {
                case OK -> {
                    String t = r.text();
                    assertFalse(t.isEmpty());
                    assertTrue(t.codePointCount(0, t.length()) <= CAP, "over cap: " + t.length());
                    assertEquals(t, t.trim(), "not trimmed");
                    assertFalse(t.contains("  "), "uncollapsed space run");
                    t.codePoints().forEach(c -> {
                        assertFalse(Character.isISOControl(c),
                                "control survived: U+" + Integer.toHexString(c));
                        assertFalse(c == 0x200B || c == 0x200D || c == 0x200F || c == 0x202E
                                        || c == 0x2060 || c == 0x2064 || c == 0xFEFF || c == 0xAD
                                        || c == 0xA7,
                                "stripped code point survived: U+" + Integer.toHexString(c));
                        assertFalse(c != ' ' && (Character.isWhitespace(c) || Character.isSpaceChar(c)),
                                "exotic whitespace survived: U+" + Integer.toHexString(c));
                    });
                }
                case EMPTY, TOO_LONG -> assertEquals("", r.text());
            }
        }
    }
}
