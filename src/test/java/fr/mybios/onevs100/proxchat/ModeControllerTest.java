package fr.mybios.onevs100.proxchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.mybios.onevs100.proxchat.api.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The single transition path behind {@link fr.mybios.onevs100.proxchat.api.ProxChatService}:
 * leaving ON clears exactly once, every actual change persists, no-ops do nothing. These rules
 * hold for BOTH levers (admin command, host-plugin drive) because both go through this class.
 */
class ModeControllerTest {

    private final ModeMachine machine = new ModeMachine();
    private final AtomicInteger clears = new AtomicInteger();
    private final List<Mode> persisted = new ArrayList<>();
    private final ModeController controller =
            new ModeController(machine, clears::incrementAndGet, persisted::add);

    @Test
    void setModeReturnsPreviousAndUpdates() {
        assertEquals(Mode.OFF, controller.setMode(Mode.ON));
        assertEquals(Mode.ON, controller.mode());
        assertEquals(Mode.ON, controller.setMode(Mode.SUPPRESSED));
        assertEquals(Mode.SUPPRESSED, controller.mode());
    }

    @Test
    void leavingOnClearsExactlyOnce() {
        controller.setMode(Mode.ON);
        controller.setMode(Mode.OFF); // ON -> OFF clears
        assertEquals(1, clears.get());
        controller.setMode(Mode.SUPPRESSED); // OFF -> SUPPRESSED must not
        assertEquals(1, clears.get());
        controller.setMode(Mode.ON);
        controller.setMode(Mode.SUPPRESSED); // ON -> SUPPRESSED clears too
        assertEquals(2, clears.get());
    }

    @Test
    void everyChangePersistsNoOpDoesNot() {
        controller.setMode(Mode.ON);
        controller.setMode(Mode.ON); // no-op: no clear, no persist (interface contract)
        controller.setMode(Mode.OFF);
        assertEquals(List.of(Mode.ON, Mode.OFF), persisted);
        assertEquals(1, clears.get());
    }

    @Test
    void defaultVerbsDelegateToSetMode() {
        assertEquals(Mode.OFF, controller.enable());
        assertEquals(Mode.ON, controller.suppress());
        assertEquals(Mode.SUPPRESSED, controller.disable());
        assertEquals(List.of(Mode.ON, Mode.SUPPRESSED, Mode.OFF), persisted);
    }

    @Test
    void clearAllDelegatesWithoutTouchingMode() {
        controller.setMode(Mode.ON);
        controller.clearAll();
        assertEquals(Mode.ON, controller.mode());
        assertEquals(1, clears.get());
        assertEquals(List.of(Mode.ON), persisted); // clearAll never persists
    }

    @Test
    void nullTargetThrowsAndChangesNothing() {
        controller.setMode(Mode.ON);
        assertThrows(NullPointerException.class, () -> controller.setMode(null));
        assertEquals(Mode.ON, controller.mode());
        assertEquals(0, clears.get());
    }
}
