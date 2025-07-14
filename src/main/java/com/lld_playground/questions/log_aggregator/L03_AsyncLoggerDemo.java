package com.lld_playground.questions.log_aggregator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
 * Mini‑logger – v3
 * -----------------
 * <p>
 * Added features over v2:
 * <ul>
 *     <li>⚙️  Asynchronous, non‑blocking dispatch (single worker thread).</li>
 *     <li>📁  Rolling file sink (simple size‑based rotation).</li>
 *     <li>🔍  Per‑logger level threshold filtering.</li>
 *     <li>🌎  ISO‑8601 timestamps for readability + easy parsing.</li>
 * </ul>
 * <p>
 * The design still keeps the synchronous API <code>log(Level, String)</code>
 * but the heavy duty I/O happens off the caller thread.  This makes the
 * logger suitable for high‑throughput systems such as a log‑aggregator
 * side‑car.
 */
public final class L03_AsyncLoggerDemo {

    /* ─────────────── 1. Levels ─────────────── */
    public enum Level {
        DEBUG(10), INFO(20), WARN(30), ERROR(40);
        final int severity;
        Level(int s) { this.severity = s; }
        /** Is <code>this</code> level enabled when a logger is configured with {@code threshold}? */
        public boolean enabledAt(Level threshold) {
            return this.severity >= threshold.severity;
        }
    }

    /* ─────────────── 2. Event ─────────────── */
    public record Event(Instant ts, Level lvl, String logger, String msg) {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;
        /** Single‑line wire‑format we append / send to sinks. */
        public String serialize() {
            return String.format("%s %-5s [%s] %s%n",
                    FMT.format(ts), lvl, logger, msg);
        }
    }

    /* ─────────────── 3. Sink ─────────────── */
    public interface Sink extends AutoCloseable {
        void write(Event e) throws Exception;
        @Override default void close() throws Exception { /* default no‑op */ }
    }

    /* ───── 4a. Console sink – println I/O ░░░ */
    public static final class ConsoleSink implements Sink {
        @Override public void write(Event e) { System.out.print(e.serialize()); }
    }

    /* ───── 4b. Rolling‑file sink – simple size rotation ░░░ */
    public static final class RollingFileSink implements Sink {
        private static final long DEFAULT_MAX_BYTES = 5 * 1024 * 1024; // 5 MiB
        private final long maxBytes;
        private final Path base;
        private int index = 0;
        private BufferedWriter out;
        private long written;

        public RollingFileSink(String baseName) {
            this(baseName, DEFAULT_MAX_BYTES);
        }
        public RollingFileSink(String baseName, long maxBytes) {
            this.base = Paths.get(baseName);
            this.maxBytes = maxBytes;
            openNewFile();
        }
        private void openNewFile() {
            try {
                if (!Files.exists(base.getParent()))
                    Files.createDirectories(base.getParent());
                Path file = index == 0 ? base : Paths.get(base + "." + index);
                out = Files.newBufferedWriter(file,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                written = Files.size(file);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot open log file", ex);
            }
        }
        private void rotateIfNeeded(long len) {
            if (written + len <= maxBytes) return;
            try { out.close(); } catch (IOException ignored) {}
            index++; openNewFile();
        }
        @Override public synchronized void write(Event e) {
            String txt = e.serialize();
            rotateIfNeeded(txt.getBytes().length);
            try {
                out.write(txt);
                written += txt.getBytes().length;
            } catch (IOException ex) { throw new RuntimeException(ex); }
        }
        @Override public void close() throws IOException { out.close(); }
    }

    /* ───── 5. Async dispatcher (fan‑out) ░░░ */
    public static final class AsyncDispatcher implements Sink {
        private final List<Sink> sinks;
        private final BlockingQueue<Event> q;
        private final ExecutorService worker;
        private volatile boolean running = true;

        public AsyncDispatcher(List<Sink> downstream, int capacity) {
            this.sinks = Objects.requireNonNull(downstream);
            this.q = new ArrayBlockingQueue<>(capacity);
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "log‑writer");
                t.setDaemon(true); return t; });
            worker.submit(this::pump);
        }
        @Override public void write(Event e) {
            if (!running) throw new IllegalStateException("dispatcher closed");
            /* if full – drop & move on, or you could BLOCK/throw depending on policy */
            q.offer(e);
        }
        private void pump() {
            try {
                while (running || !q.isEmpty()) {
                    Event e = q.poll(500, TimeUnit.MILLISECONDS);
                    if (e == null) continue;
                    for (Sink s : sinks) try { s.write(e); } catch (Exception ex) { /* swallow */ }
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        @Override public void close() throws Exception {
            running = false;
            worker.shutdown();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            for (Sink s : sinks) s.close();
        }
    }

    /* ───── 6. Logger facade ░░░ */
    public static final class Logger implements AutoCloseable {
        private final String name;
        private final Level threshold;
        private final Sink sink;
        public Logger(String name, Level threshold, Sink sink) {
            this.name = name; this.threshold = threshold; this.sink = sink;
        }
        /* convenience methods */
        public void debug(String m) { log(Level.DEBUG, m); }
        public void info (String m) { log(Level.INFO , m); }
        public void warn (String m) { log(Level.WARN , m); }
        public void error(String m) { log(Level.ERROR, m); }
        public void log(Level lvl, String msg) {
            if (!lvl.enabledAt(threshold)) return;
            try { sink.write(new Event(Instant.now(), lvl, name, msg)); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        }
        @Override public void close() throws Exception { sink.close(); }
    }

    /* ───── 7. Factory helper ░░░ */
    public static Logger newAsyncLogger(String name) {
        Sink console = new ConsoleSink();
        Sink rolling = new RollingFileSink("logs/app.log");
        Sink dispatcher = new AsyncDispatcher(List.of(console, rolling), /*queue*/ 10_000);
        return new Logger(name, Level.DEBUG, dispatcher);
    }

    /* ───── 8. Demo ░░░ */
    public static void main(String[] args) throws Exception {
        try (Logger log = newAsyncLogger("main")) {
            log.info("🚀 starting async demo");
            for (int i = 0; i < 1_000; i++) log.debug("line " + i);
            log.warn("about to finish");
        }
        // give async dispatcher a moment to flush (mainly for demo)
        Thread.sleep(500);
    }
}
