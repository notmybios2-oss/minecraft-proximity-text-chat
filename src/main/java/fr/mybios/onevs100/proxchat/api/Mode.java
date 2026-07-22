package fr.mybios.onevs100.proxchat.api;

/**
 * Plugin-wide chat mode. Part of the frozen {@link ProxChatService} surface — host plugins
 * compile against this enum, so names and semantics must not change.
 *
 * <p><b>OFF ≡ SUPPRESSED to players under a host lockdown</b>: a host plugin cancelling every
 * chat broadcast at LOWEST makes both modes show no chat and no bubble — observably identical.
 * They differ in <i>mechanism</i>, which matters standalone and for fail posture: OFF leaves
 * the chat event untouched (on a bare server vanilla chat would flow), while SUPPRESSED
 * actively cancels and swallows it (chat stays dead even with no lockdown underneath). A host
 * drives SUPPRESSED when even a bubble would reveal a hidden presence.
 */
public enum Mode {
    /** Inert: chat events untouched (a host lockdown may still keep the broadcast dead). */
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
