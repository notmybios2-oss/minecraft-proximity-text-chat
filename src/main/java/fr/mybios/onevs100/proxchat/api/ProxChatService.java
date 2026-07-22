package fr.mybios.onevs100.proxchat.api;

/**
 * The FROZEN integration surface. Host plugins build their mode drive against exactly this
 * interface — changing it is a breaking change to other plugins and must be treated as such.
 *
 * <p><b>Registration:</b> ProxChat registers an implementation with Bukkit's
 * {@code ServicesManager} at {@code ServicePriority.Normal} during {@code onEnable} and
 * unregisters on disable. Consumers should declare {@code softdepend: [ProxChat]} and look the
 * service up lazily at use time (registration order is then irrelevant):
 * {@code getServer().getServicesManager().load(ProxChatService.class)} — null means ProxChat is
 * absent/disabled, which consumers must treat as "no proximity chat" (fail-open for them, the
 * broadcast stays dead via the lockdown either way).
 *
 * <p><b>Compile-time:</b> consumers add the prox-chat jar as {@code compileOnly}; this package
 * ({@code …proxchat.api}) is the only one they may import.
 *
 * <p><b>Thread contract: every method is callable from ANY thread</b> (global region, a region
 * tick, async). Mode flips are atomic; bubble clears fan out asynchronously to each speaker's
 * region thread and normally complete within that fan-out. A message racing the flip can slip
 * past the fan-out in a microsecond window; the publish path re-checks the mode and the 0.5 s
 * per-player reconciler removes anything residual at its next beat — no bubble outlives one
 * heartbeat period after a flip away from ON.
 *
 * <p><b>Side effects of {@link #setMode}:</b> leaving ON clears every live bubble (SUPPRESSED
 * means "show nothing", and OFF must not strand text for up to lifetime-seconds — bounded as
 * above). Every actual change is self-persisted asynchronously with an atomic write; on restart
 * the last successfully PERSISTED mode is restored (first boot: OFF; unreadable file: OFF,
 * loudly). A crash inside the async write window can restore the previous mode, so a consumer
 * that must hold an anonymity-critical window across restarts (SUPPRESSED) should re-assert the
 * mode from its own state-restore path rather than rely on this persistence alone. A same-mode
 * set is a no-op: no clear, no persist, no side effects.
 */
public interface ProxChatService {

    /** Current mode — atomic volatile read, any thread. */
    Mode mode();

    /**
     * Switches the mode and returns the PREVIOUS one (callers log transitions). Never null in,
     * never null out; a no-op set (target == current) returns the same value and does nothing.
     *
     * @throws NullPointerException if {@code target} is null
     */
    Mode setMode(Mode target);

    /** {@code setMode(Mode.ON)} — the stage-1 flip (same instant as voice, house invariant). */
    default Mode enable() {
        return setMode(Mode.ON);
    }

    /** {@code setMode(Mode.SUPPRESSED)} — pause/stasis/end-theater ("show nothing"). */
    default Mode suppress() {
        return setMode(Mode.SUPPRESSED);
    }

    /** {@code setMode(Mode.OFF)} — back to inert (LOBBY posture). */
    default Mode disable() {
        return setMode(Mode.OFF);
    }

    /**
     * Removes every live bubble without touching the mode.
     * Fans out to each speaker's region thread; safe from any thread.
     */
    void clearAll();
}
