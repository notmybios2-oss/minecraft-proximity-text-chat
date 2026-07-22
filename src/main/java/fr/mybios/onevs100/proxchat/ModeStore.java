package fr.mybios.onevs100.proxchat;

import fr.mybios.onevs100.proxchat.api.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * Crash-safe self-persistence for the mode, deliberately calibrated to what it guards: ONE enum
 * whose worst-case loss is "boots OFF" — which is the fail-closed first-boot default anyway, is
 * loud (the boot log states the restore outcome), and is self-correcting (a host plugin's mode
 * drive or one admin command re-asserts it). So: atomic write + strict versioned parse +
 * fail-LOUD-to-OFF on anything unreadable, and no heavier machinery (no epoch markers, no .bak
 * chain, no read-back verification). If this file ever guards more than the mode, re-run that
 * calibration.
 *
 * <p>Write is atomic: serialize → temp file → {@code ATOMIC_MOVE} (plain replace where the
 * filesystem lacks it). {@link #persistCurrent} is synchronized and reads the live mode INSIDE
 * the lock, so concurrent flips can never interleave read/write windows and strand a stale value
 * as the last write — every write reflects a mode that was current while the lock was held, and
 * each flip schedules a fresh write, so the file converges to the latest mode.
 */
public final class ModeStore {

    private static final String VERSION = "1";

    private final Path file;
    private final Path tmp;

    public ModeStore(Path file) {
        this.file = file;
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
    }

    /** Restore outcomes — the caller logs each differently (first boot is not a warning). */
    public enum Outcome {
        /** No file: genuine first boot. Mode is OFF by design (fail-closed). */
        FIRST_BOOT,
        /** File parsed; {@code mode} is the restored value. */
        RESTORED,
        /** A file EXISTS but is unreadable/malformed: default OFF and say so loudly. */
        UNREADABLE
    }

    public record Restore(Outcome outcome, Mode mode, String detail) {
    }

    /** Never throws: every failure path degrades to a loud OFF. */
    public Restore load() {
        if (!Files.exists(file)) {
            return new Restore(Outcome.FIRST_BOOT, Mode.OFF, "no mode file (first boot)");
        }
        try {
            Mode restored = parse(Files.readString(file, StandardCharsets.UTF_8));
            return new Restore(Outcome.RESTORED, restored, "restored from " + file.getFileName());
        } catch (Exception e) {
            return new Restore(Outcome.UNREADABLE, Mode.OFF,
                    file.getFileName() + " exists but is unreadable (" + e.getMessage()
                            + ") — defaulting OFF; re-set the mode and it will be rewritten");
        }
    }

    /**
     * Atomically persists the CURRENT mode. The supplier is read inside the lock — see the class
     * javadoc for why that (not the caller's captured value) is what makes concurrent flips
     * converge. Callers hop to the async scheduler; this must never run on a region thread.
     */
    public synchronized void persistCurrent(Supplier<Mode> current) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(tmp, serialize(current.get()), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- serialization (pure, unit-tested; format mirrors GameStatePersistence's key=value) ----

    static String serialize(Mode mode) {
        return "v=" + VERSION + "\nmode=" + mode.name() + "\n";
    }

    /** Strict parse: wrong/missing version or mode is corruption, never a silent default. */
    static Mode parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty mode file");
        }
        String version = null;
        String mode = null;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("bad line (no '='): " + line);
            }
            switch (line.substring(0, eq)) {
                case "v" -> version = line.substring(eq + 1);
                case "mode" -> mode = line.substring(eq + 1);
                default -> { /* ignore unknown keys for forward-compat */ }
            }
        }
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("unsupported/missing version: " + version);
        }
        if (mode == null) {
            throw new IllegalArgumentException("missing mode field");
        }
        try {
            return Mode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }
}
