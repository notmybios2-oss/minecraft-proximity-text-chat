package fr.mybios.onevs100.proxchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.mybios.onevs100.proxchat.api.Mode;
import org.junit.jupiter.api.Test;

/** Mode machine transitions. Persistence round-trip: ModeStoreTest. */
class ModeMachineTest {

    @Test
    void firstBootIsOff() {
        assertEquals(Mode.OFF, new ModeMachine().current()); // fail-closed first boot
    }

    @Test
    void setReturnsPrevious() {
        ModeMachine machine = new ModeMachine();
        assertEquals(Mode.OFF, machine.set(Mode.ON));
        assertEquals(Mode.ON, machine.set(Mode.SUPPRESSED));
        assertEquals(Mode.SUPPRESSED, machine.set(Mode.OFF));
        assertEquals(Mode.OFF, machine.current());
    }

    @Test
    void interceptAndRenderSemantics() {
        // OFF: untouched. ON: intercept + bubble. SUPPRESSED: intercept, swallow (§3.6).
        assertFalse(Mode.OFF.intercepts());
        assertFalse(Mode.OFF.renders());
        assertTrue(Mode.ON.intercepts());
        assertTrue(Mode.ON.renders());
        assertTrue(Mode.SUPPRESSED.intercepts());
        assertFalse(Mode.SUPPRESSED.renders());
    }
}
