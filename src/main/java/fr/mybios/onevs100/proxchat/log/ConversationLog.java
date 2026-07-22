package fr.mybios.onevs100.proxchat.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * Server-side bubble-conversation record: one JSONL event per line, daily files
 * {@code <dir>/bubbles-YYYY-MM-DD.jsonl}, designed to be tailed live by a future consumer
 * (the format is the interface). Content appears ONLY in these files — never in the server
 * log — and only while {@code conversation-log.enabled} is true (default off).
 *
 * <p>THREAD MODEL: producers (speaker region threads) build the JSON line and make one
 * non-blocking {@link #submit} — logging can never block or delay a bubble. A single daemon
 * writer thread owns all file I/O: open, per-line flush (tail-friendly), midnight rollover by
 * filename, and retention pruning at each file open ({@code retention-days: 0} = keep forever,
 * the ruled default; pruning never touches the file being opened). Queue overflow or an I/O
 * failure drops the line, counts it, and warns at most once per 30 s.
 *
 * <p>{@link #close()} stops intake, drains whatever is queued, and bounds the wait — a server
 * stop is never stalled by this log.
 */
public final class ConversationLog implements AutoCloseable {

    static final String FILE_PREFIX = "bubbles-";
    static final String FILE_SUFFIX = ".jsonl";
    private static final int DEFAULT_CAPACITY = 8192;
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long CLOSE_DRAIN_BOUND_MS = 2000;

    /** Queued event: the line is fully built by the producer; tsMs picks the day file. */
    private record Line(long tsMs, String json) {}

    private final Path directory;
    /** Retention is re-read live (config reload retunes it like every other key). */
    private final IntSupplier retentionDays;
    private final Logger logger;
    private final Clock clock;
    private final BlockingQueue<Line> queue;
    private final Thread writer;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastWarnNanos = new AtomicLong(Long.MIN_VALUE);
    private volatile boolean closed;

    public ConversationLog(Path directory, IntSupplier retentionDays, Logger logger) {
        this(directory, retentionDays, logger, Clock.systemDefaultZone(), DEFAULT_CAPACITY, true);
    }

    /** Test seam: injectable clock, small capacities, and a writer that never starts. */
    ConversationLog(Path directory, IntSupplier retentionDays, Logger logger,
                    Clock clock, int capacity, boolean startWriter) {
        this.directory = directory;
        this.retentionDays = retentionDays;
        this.logger = logger;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = new Thread(this::runWriter, "proxchat-conversation-log");
        this.writer.setDaemon(true);
        if (startWriter) {
            this.writer.start();
        }
    }

    // ------------------------------------------------------------------ producer side

    /**
     * Non-blocking enqueue from any thread. A full queue or a closed log drops the line
     * (counted + rate-limited WARN) — the bubble pipeline must never wait on disk.
     */
    public void submit(long tsMs, String jsonLine) {
        if (closed || !queue.offer(new Line(tsMs, jsonLine))) {
            long total = dropped.incrementAndGet();
            maybeWarn(total);
        }
    }

    long droppedCount() {
        return dropped.get();
    }

    private void maybeWarn(long totalDropped) {
        long now = System.nanoTime();
        long last = lastWarnNanos.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
            logger.warning("conversation log dropped " + totalDropped
                    + " event(s) total (queue full, I/O failure, or shutdown) — bubbles unaffected");
        }
    }

    // ------------------------------------------------------------------ writer side

    private LocalDate openDate;
    private BufferedWriter out;

    private void runWriter() {
        while (true) {
            Line line;
            try {
                line = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break; // close() interrupted us — fall through to the bounded drain
            }
            if (line != null) {
                write(line);
            } else if (closed) {
                break;
            }
        }
        // Drain what made it into the queue before intake stopped, then close.
        Line remaining;
        while ((remaining = queue.poll()) != null) {
            write(remaining);
        }
        closeStream();
    }

    private void write(Line line) {
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(line.tsMs()), clock.getZone());
        try {
            if (out == null || !day.equals(openDate)) {
                rotateTo(day);
            }
            out.write(line.json());
            out.write('\n');
            out.flush(); // per-line: a tail sees the event the moment it happened
        } catch (IOException e) {
            closeStream(); // reopen is attempted on the next line
            long total = dropped.incrementAndGet();
            maybeWarn(total);
        }
    }

    private void rotateTo(LocalDate day) throws IOException {
        closeStream();
        Files.createDirectories(directory);
        prune(day);
        Path file = directory.resolve(FILE_PREFIX + DateTimeFormatter.ISO_LOCAL_DATE.format(day) + FILE_SUFFIX);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        openDate = day;
    }

    /**
     * Deletes day files older than {@code retention-days}, by FILENAME date (authoritative —
     * mtime lies after copies/restores). 0 or negative = keep forever (the ruled default:
     * "no auto delete, keep until asked"). The file being opened is structurally safe: its
     * date can never be strictly before the cutoff derived from itself.
     */
    private void prune(LocalDate opening) {
        int days = retentionDays.getAsInt();
        if (days <= 0) {
            return;
        }
        LocalDate cutoff = opening.minus(days, ChronoUnit.DAYS);
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(directory,
                FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path candidate : dir) {
                LocalDate date = dateOf(candidate.getFileName().toString());
                if (date != null && date.isBefore(cutoff)) {
                    Files.deleteIfExists(candidate);
                }
            }
        } catch (IOException e) {
            logger.warning("conversation log retention prune failed: " + e.getMessage());
        }
    }

    private static LocalDate dateOf(String fileName) {
        String middle = fileName.substring(FILE_PREFIX.length(),
                fileName.length() - FILE_SUFFIX.length());
        try {
            return LocalDate.parse(middle, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException notADayFile) {
            return null; // foreign file matching the glob — never delete what we can't parse
        }
    }

    private void closeStream() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // stream is being discarded either way
            }
            out = null;
            openDate = null;
        }
    }

    /** Stops intake, drains the queue on the writer thread, bounded — never stalls a stop. */
    @Override
    public void close() {
        closed = true;
        writer.interrupt();
        try {
            writer.join(CLOSE_DRAIN_BOUND_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writer.isAlive()) {
            logger.warning("conversation log writer still draining at close — abandoned (daemon)");
        }
    }

    // ------------------------------------------------------------------ event lines (pure)

    /**
     * A rendered bubble: who spoke, exactly what rendered (sanitized text), and who could read
     * it the tick it appeared (the at-send admitted set, speaker excluded — the speaker
     * trivially reads their own). A sneak-hidden spawn correctly records an empty audience;
     * the reveal produces {@code admit} events.
     */
    public static String msgLine(long tsMs, java.time.ZoneId zone, String speakerName,
                                 UUID speakerId, String msg, List<Participant> audience,
                                 String world, int x, int y, int z) {
        StringBuilder sb = head("msg", tsMs, zone, speakerName, speakerId);
        sb.append(",\"msg\":\"").append(escape(msg)).append("\",\"audience\":[");
        for (int i = 0; i < audience.size(); i++) {
            Participant p = audience.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(escape(p.name()))
                    .append("\",\"uuid\":\"").append(p.uuid()).append("\"}");
        }
        sb.append(']');
        return tail(sb, world, x, y, z);
    }

    /** A late entrant: a viewer admitted to a speaker's live stack after the send. */
    public static String admitLine(long tsMs, java.time.ZoneId zone, String speakerName,
                                   UUID speakerId, String viewerName, UUID viewerId,
                                   String world, int x, int y, int z) {
        StringBuilder sb = head("admit", tsMs, zone, speakerName, speakerId);
        sb.append(",\"viewer\":{\"name\":\"").append(escape(viewerName))
                .append("\",\"uuid\":\"").append(viewerId).append("\"}");
        return tail(sb, world, x, y, z);
    }

    public record Participant(String name, UUID uuid) {}

    private static StringBuilder head(String type, long tsMs, java.time.ZoneId zone,
                                      String speakerName, UUID speakerId) {
        String iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(tsMs), zone));
        return new StringBuilder(256)
                .append("{\"type\":\"").append(type)
                .append("\",\"ts\":\"").append(iso)
                .append("\",\"ts_ms\":").append(tsMs)
                .append(",\"speaker\":{\"name\":\"").append(escape(speakerName))
                .append("\",\"uuid\":\"").append(speakerId).append("\"}");
    }

    private static String tail(StringBuilder sb, String world, int x, int y, int z) {
        return sb.append(",\"world\":\"").append(escape(world))
                .append("\",\"x\":").append(x)
                .append(",\"y\":").append(y)
                .append(",\"z\":").append(z)
                .append('}').toString();
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public java.time.ZoneId zone() {
        return clock.getZone();
    }
}
