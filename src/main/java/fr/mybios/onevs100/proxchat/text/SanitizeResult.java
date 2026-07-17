package fr.mybios.onevs100.proxchat.text;

/**
 * Outcome of {@link MessageSanitizer#sanitize}. {@code text} is non-empty exactly when the
 * verdict is {@link Verdict#OK} — rejected input never travels further than this record
 * (nothing is stored or logged; messages exist only as short-lived entities, pc-001 §3.8).
 */
public record SanitizeResult(Verdict verdict, String text) {

    public enum Verdict {
        /** Clean, bounded, single-line text — safe to render as a literal component. */
        OK,
        /** Nothing renderable survived sanitization: no bubble, no feedback (silent). */
        EMPTY,
        /** Over the code-point cap: REJECT with the red FR line (owner Q4) — never truncate. */
        TOO_LONG
    }

    static SanitizeResult ok(String text) {
        return new SanitizeResult(Verdict.OK, text);
    }

    static SanitizeResult empty() {
        return new SanitizeResult(Verdict.EMPTY, "");
    }

    static SanitizeResult tooLong() {
        return new SanitizeResult(Verdict.TOO_LONG, "");
    }
}
