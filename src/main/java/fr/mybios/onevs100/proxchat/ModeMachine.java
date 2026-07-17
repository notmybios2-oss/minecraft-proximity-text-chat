package fr.mybios.onevs100.proxchat;

import fr.mybios.onevs100.proxchat.api.Mode;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current {@link Mode}. Read from the async chat thread and region threads, written
 * from any thread (command, console, EventCore's drive) — hence atomic. First boot is OFF
 * (pc-002 Q8: fail-closed until the game, or an admin, says otherwise); {@link ModeStore}
 * restores the persisted mode over this default at enable. All transitions go through
 * {@link ModeController} — only boot-time restore writes here directly (no side effects owed).
 */
public final class ModeMachine {

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OFF);

    public Mode current() {
        return mode.get();
    }

    /** Sets the mode and returns the previous one (callers log transitions in English). */
    public Mode set(Mode next) {
        return mode.getAndSet(next);
    }
}
