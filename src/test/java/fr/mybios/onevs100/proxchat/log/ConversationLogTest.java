package fr.mybios.onevs100.proxchat.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writer mechanics run against a real temp directory with an injected fixed clock — rollover
 * and retention are pure functions of the event timestamps, never of the wall clock, so the
 * tests are deterministic. Golden-line assertions pin the JSONL shape: the format is the
 * interface a future consumer will tail, so a shape change must be a deliberate test change.
 */
class ConversationLogTest {

    private static final Logger LOG = Logger.getLogger("ConversationLogTest");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final UUID SPEAKER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // 2026-07-21T20:41:03.512+02:00 (Paris, DST)
    private static final long TS = 1784659263512L;

    @TempDir
    Path dir;

    private ConversationLog openedLog(int retentionDays) {
        return new ConversationLog(dir, () -> retentionDays, LOG,
                Clock.fixed(Instant.ofEpochMilli(TS), PARIS), 64, true);
    }

    private static void awaitFile(Path file, int lines) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (Files.exists(file) && Files.readAllLines(file).size() >= lines) {
                return;
            }
            Thread.sleep(10);
        }
    }

    // ---------------------------------------------------------------- golden line shapes

    @Test
    void msgLineGoldenShape() {
        String line = ConversationLog.msgLine(TS, PARIS, "Alice", SPEAKER, "on va où là ?",
                List.of(new ConversationLog.Participant("Bob", VIEWER)), "world", 123, 64, -88);
        assertEquals("{\"type\":\"msg\",\"ts\":\"2026-07-21T20:41:03.512+02:00\",\"ts_ms\":" + TS
                + ",\"speaker\":{\"name\":\"Alice\",\"uuid\":\"" + SPEAKER + "\"}"
                + ",\"msg\":\"on va où là ?\",\"audience\":[{\"name\":\"Bob\",\"uuid\":\""
                + VIEWER + "\"}],\"world\":\"world\",\"x\":123,\"y\":64,\"z\":-88}", line);
    }

    @Test
    void admitLineGoldenShape() {
        String line = ConversationLog.admitLine(TS, PARIS, "Alice", SPEAKER, "Bob", VIEWER,
                "world", 1, 2, 3);
        assertEquals("{\"type\":\"admit\",\"ts\":\"2026-07-21T20:41:03.512+02:00\",\"ts_ms\":" + TS
                + ",\"speaker\":{\"name\":\"Alice\",\"uuid\":\"" + SPEAKER + "\"}"
                + ",\"viewer\":{\"name\":\"Bob\",\"uuid\":\"" + VIEWER + "\"}"
                + ",\"world\":\"world\",\"x\":1,\"y\":2,\"z\":3}", line);
    }

    @Test
    void emptyAudienceIsAnEmptyArray() {
        // A sneak-hidden spawn renders to nobody: the record must say so, not omit the field.
        String line = ConversationLog.msgLine(TS, PARIS, "Alice", SPEAKER, "chut",
                List.of(), "world", 0, 0, 0);
        assertTrue(line.contains("\"audience\":[]"));
    }

    @Test
    void jsonEscapingHoldsForQuotesBackslashesAndControls() {
        String line = ConversationLog.msgLine(TS, PARIS, "A\"B\\C", SPEAKER, "dit \"salut\"",
                List.of(), "w" + (char) 1 + "orld", 0, 0, 0);
        assertTrue(line.contains("\"name\":\"A\\\"B\\\\C\""));
        assertTrue(line.contains("\"msg\":\"dit \\\"salut\\\"\""));
        assertTrue(line.contains("\"world\":\"w\\u0001orld\""));
    }

    // ---------------------------------------------------------------- writer mechanics

    @Test
    void submittedLinesLandInTheDayFileWhileTheLogIsStillOpen() throws Exception {
        try (ConversationLog log = openedLog(0)) {
            log.submit(TS, "{\"n\":1}");
            log.submit(TS, "{\"n\":2}");
            Path file = dir.resolve("bubbles-2026-07-21.jsonl");
            awaitFile(file, 2);
            // Read BEFORE close: per-line flush is the tail -f contract.
            assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), Files.readAllLines(file));
        }
    }

    @Test
    void closeDrainsEverythingAlreadyQueued() throws Exception {
        ConversationLog log = openedLog(0);
        for (int i = 0; i < 50; i++) {
            log.submit(TS, "{\"n\":" + i + "}");
        }
        log.close(); // bounded drain must still deliver all 50
        List<String> lines = Files.readAllLines(dir.resolve("bubbles-2026-07-21.jsonl"));
        assertEquals(50, lines.size());
        assertEquals("{\"n\":49}", lines.get(49));
    }

    @Test
    void eventTimestampCrossingMidnightOpensTheNextDayFile() throws Exception {
        long nextDay = TS + 24 * 3600 * 1000L;
        try (ConversationLog log = openedLog(0)) {
            log.submit(TS, "{\"day\":1}");
            log.submit(nextDay, "{\"day\":2}");
            awaitFile(dir.resolve("bubbles-2026-07-22.jsonl"), 1);
        }
        assertEquals(List.of("{\"day\":1}"),
                Files.readAllLines(dir.resolve("bubbles-2026-07-21.jsonl")));
        assertEquals(List.of("{\"day\":2}"),
                Files.readAllLines(dir.resolve("bubbles-2026-07-22.jsonl")));
    }

    // ---------------------------------------------------------------- retention

    private Path plantDayFile(String date) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve("bubbles-" + date + ".jsonl"), "{\"old\":true}\n");
    }

    @Test
    void retentionZeroNeverDeletes() throws Exception {
        // The ruled default: "no auto delete, keep until asked" — a year-old file survives.
        Path ancient = plantDayFile("2025-07-21");
        try (ConversationLog log = openedLog(0)) {
            log.submit(TS, "{\"n\":1}");
            awaitFile(dir.resolve("bubbles-2026-07-21.jsonl"), 1);
        }
        assertTrue(Files.exists(ancient));
    }

    @Test
    void positiveRetentionPrunesByFilenameDateOnly() throws Exception {
        Path stale = plantDayFile("2026-07-10");   // 11 days before TS — outside 5-day window
        Path fresh = plantDayFile("2026-07-19");   // 2 days before TS — inside
        Path foreign = Files.writeString(dir.resolve("bubbles-notadate.jsonl"), "keep\n");
        try (ConversationLog log = openedLog(5)) {
            log.submit(TS, "{\"n\":1}");
            awaitFile(dir.resolve("bubbles-2026-07-21.jsonl"), 1);
        }
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(foreign)); // unparseable names are never deleted
        assertTrue(Files.exists(dir.resolve("bubbles-2026-07-21.jsonl"))); // active file safe
    }

    // ---------------------------------------------------------------- drop accounting

    @Test
    void overflowDropsInsteadOfBlockingAndCounts() {
        // Writer never started: the queue (capacity 2) fills and stays full — offer must
        // return immediately and count, exactly like a stalled disk would look to producers.
        ConversationLog log = new ConversationLog(dir, () -> 0, LOG,
                Clock.fixed(Instant.ofEpochMilli(TS), PARIS), 2, false);
        for (int i = 0; i < 10; i++) {
            log.submit(TS, "{\"n\":" + i + "}");
        }
        assertEquals(8, log.droppedCount());
    }

    @Test
    void submitAfterCloseDropsAndCounts() {
        ConversationLog log = openedLog(0);
        log.close();
        log.submit(TS, "{\"late\":true}");
        assertEquals(1, log.droppedCount());
    }
}
