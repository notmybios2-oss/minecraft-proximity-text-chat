package fr.mybios.onevs100.proxchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.mybios.onevs100.proxchat.api.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash-safe mode persistence. Every unreadable-file shape must land on a loud
 * OFF, never an exception and never a silent wrong mode.
 */
class ModeStoreTest {

    @TempDir
    Path dir;

    private ModeStore store() {
        return new ModeStore(dir.resolve("mode.dat"));
    }

    @Test
    void roundTripsEveryMode() throws IOException {
        for (Mode mode : Mode.values()) {
            ModeStore store = new ModeStore(dir.resolve("mode-" + mode + ".dat"));
            store.persistCurrent(() -> mode);
            ModeStore.Restore restored = store.load();
            assertEquals(ModeStore.Outcome.RESTORED, restored.outcome());
            assertEquals(mode, restored.mode());
        }
    }

    @Test
    void missingFileIsFirstBootOff() {
        ModeStore.Restore restored = store().load();
        assertEquals(ModeStore.Outcome.FIRST_BOOT, restored.outcome());
        assertEquals(Mode.OFF, restored.mode());
    }

    @Test
    void laterWriteReplacesEarlier() throws IOException {
        ModeStore store = store();
        store.persistCurrent(() -> Mode.ON);
        store.persistCurrent(() -> Mode.SUPPRESSED);
        assertEquals(Mode.SUPPRESSED, store.load().mode());
    }

    @Test
    void noTempResidueAfterWrite() throws IOException {
        ModeStore store = store();
        store.persistCurrent(() -> Mode.ON);
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(p -> p.getFileName().toString().equals("mode.dat")),
                    "only the live file may remain after an atomic write");
        }
    }

    @Test
    void createsParentDirectories() throws IOException {
        // First boot: the plugin data folder may not exist yet when the first flip persists.
        ModeStore store = new ModeStore(dir.resolve("nested/deeper/mode.dat"));
        store.persistCurrent(() -> Mode.ON);
        assertEquals(Mode.ON, store.load().mode());
    }

    @Test
    void garbageFileIsLoudOffNotException() throws IOException {
        Files.writeString(dir.resolve("mode.dat"), "not a mode file");
        ModeStore.Restore restored = store().load();
        assertEquals(ModeStore.Outcome.UNREADABLE, restored.outcome());
        assertEquals(Mode.OFF, restored.mode());
        assertFalse(restored.detail().isBlank(), "the loud log line needs a reason");
    }

    @Test
    void truncatedAndForeignShapesAllFailLoudToOff() throws IOException {
        // Strict parse: each shape is corruption, never a silent default (precedent discipline).
        for (String bad : new String[] {
                "", "\n", "v=1\n", "mode=ON\n", "v=2\nmode=ON\n", "v=1\nmode=LOUD\n", "v=1\nmode=on\n"
        }) {
            Files.writeString(dir.resolve("mode.dat"), bad);
            ModeStore.Restore restored = store().load();
            assertEquals(ModeStore.Outcome.UNREADABLE, restored.outcome(), "input: " + bad.replace("\n", "\\n"));
            assertEquals(Mode.OFF, restored.mode());
        }
    }

    @Test
    void unknownKeysAreForwardCompatible() throws IOException {
        // A later version may add fields; an older jar must still restore the mode it knows.
        Files.writeString(dir.resolve("mode.dat"), "v=1\nmode=SUPPRESSED\nfuture=stuff\n");
        ModeStore.Restore restored = store().load();
        assertEquals(ModeStore.Outcome.RESTORED, restored.outcome());
        assertEquals(Mode.SUPPRESSED, restored.mode());
    }

    @Test
    void serializeParsePureRoundTrip() {
        for (Mode mode : Mode.values()) {
            assertEquals(mode, ModeStore.parse(ModeStore.serialize(mode)));
        }
        assertThrows(IllegalArgumentException.class, () -> ModeStore.parse("v=1\nmode=\n"));
        assertThrows(IllegalArgumentException.class, () -> ModeStore.parse("v=1 mode=ON"));
    }
}
