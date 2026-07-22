package fr.mybios.onevs100.proxchat;

import fr.mybios.onevs100.proxchat.api.Mode;
import fr.mybios.onevs100.proxchat.api.ProxChatService;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The single mode-transition path — the {@link ProxChatService} implementation that BOTH the
 * admin command and a host plugin's mode drive go through, so leaving-ON-clears and persistence
 * can never diverge between the two levers. Pure wiring (no Bukkit imports): the clear fan-out and
 * the async persist hop are injected by {@link ProxChat}, which keeps every transition rule
 * unit-testable.
 *
 * <p>Thread contract per the interface: any thread. {@link ModeMachine} is atomic; the injected
 * actions are themselves any-thread (clearAll fans out via entity schedulers, persist hops to
 * the async scheduler).
 */
public final class ModeController implements ProxChatService {

    private final ModeMachine modes;
    private final Runnable clearAllAction;
    private final Consumer<Mode> persistAction;

    public ModeController(ModeMachine modes, Runnable clearAllAction, Consumer<Mode> persistAction) {
        this.modes = modes;
        this.clearAllAction = clearAllAction;
        this.persistAction = persistAction;
    }

    @Override
    public Mode mode() {
        return modes.current();
    }

    @Override
    public Mode setMode(Mode target) {
        Objects.requireNonNull(target, "target");
        Mode previous = modes.set(target);
        if (previous != target) {
            if (previous == Mode.ON) {
                // Leaving ON: SUPPRESSED means "show nothing" and OFF must not strand text
                // for up to lifetime-seconds either (deliberate judgment call).
                clearAllAction.run();
            }
            persistAction.accept(target);
        }
        return previous;
    }

    @Override
    public void clearAll() {
        clearAllAction.run();
    }
}
