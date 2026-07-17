package fr.mybios.onevs100.proxchat.api;

/**
 * Plugin-wide chat mode (pc-001 §3.6). Part of the frozen {@link ProxChatService} surface —
 * EventCore compiles against this enum, so names and semantics must not change (pc-003 slice 3).
 *
 * <p><b>OFF ≡ SUPPRESSED to players on prod</b>: EventCore's lockdown cancels every chat
 * broadcast at LOWEST in every game state, so in both modes players see no chat and no bubble —
 * the two are observably identical. They differ in <i>mechanism</i>, which matters standalone
 * and for fail posture: OFF leaves the event untouched (on a bare server vanilla chat would
 * flow), while SUPPRESSED actively cancels and swallows it (chat stays dead even with no
 * lockdown underneath). The game drives SUPPRESSED when even a bubble would reveal a hidden
 * presence (pause/stasis, end-theater window).
 */
public enum Mode {
    /** Inert: chat events untouched. On prod EventCore still cancels every broadcast at LOWEST. */
    OFF,
    /** Intercept chat and render proximity bubbles. */
    ON,
    /** Intercept and swallow: no bubble, no broadcast — even a bubble would reveal a hidden presence. */
    SUPPRESSED;

    /** Whether chat events are consumed (cancelled) in this mode. */
    public boolean intercepts() {
        return this != OFF;
    }

    /** Whether accepted messages become bubbles in this mode. */
    public boolean renders() {
        return this == ON;
    }
}
