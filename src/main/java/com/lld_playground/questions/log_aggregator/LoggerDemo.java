package com.lld_playground.questions.log_aggregator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Everything (API, impl, and demo) in one file for quick copy–paste runs. */
public class LoggerDemo {

    /* ─────────────────────────────  MODEL  ─────────────────────────────── */

    /** Severity levels. */
    enum LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

    /** Immutable, append-only value object. */
    record LogEvent(Instant timestamp,
                    LogLevel level,
                    String threadName,
                    String loggerName,
                    String message,
                    Map<String, String> context) {}

    /* ─────────────────────────────  SINKS  ─────────────────────────────── */

    /** “Where logs go.”  Implementations must be thread-safe. */
    interface LogSink extends AutoCloseable {
        void writeBatch(List<LogEvent> events) throws Exception;
        @Override default void close() throws Exception {}   // optional
    }

    /** Pretty prints to stdout. */
    static final class ConsoleSink implements LogSink {
        @Override
        public void writeBatch(List<LogEvent> events) {
            for (LogEvent e : events) {
                System.out.printf("%s  %-5s [%s] %s%n",
                        e.timestamp(), e.level(), e.threadName(), e.message());
            }
        }
    }

    /** Writes to rotating files (“app-YYYYMMDDHHMMSS.log”). */
    static final class FileSink implements LogSink {
        private final Path dir;
        private final long maxBytes;
        private BufferedWriter writer;
        private long writtenBytes = 0;

        FileSink(Path dir, long maxBytes) throws IOException {
            this.dir = dir;
            this.maxBytes = maxBytes;
            Files.createDirectories(dir);
            rotate(); // open first file
        }

        private void rotate() throws IOException {
            if (writer != null) writer.close();
            String fname = "app-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                    ".log";
            writer = Files.newBufferedWriter(dir.resolve(fname), StandardOpenOption.CREATE_NEW);
            writtenBytes = 0;
        }

        @Override
        public synchronized void writeBatch(List<LogEvent> batch) throws IOException {
            for (LogEvent e : batch) {
                String line = String.format("%s  %-5s [%s] %s%n",
                        e.timestamp(), e.level(), e.threadName(), e.message());
                byte[] bytes = line.getBytes();
                if (writtenBytes + bytes.length > maxBytes) rotate();
                writer.write(line);
                writtenBytes += bytes.length;
            }
            writer.flush();
        }

        @Override public void close() throws IOException { if (writer != null) writer.close(); }
    }

    /* ─────────────────────  BUFFERING & DISPATCH  ──────────────────────── */

    /** Strategy when async queue is full. */
    enum BackPressurePolicy { BLOCK, DROP }

    /** One daemon thread drains a bounded queue and fan-outs batches to sinks. */
    static final class AsyncDispatcher implements AutoCloseable {
        private final BlockingQueue<LogEvent> queue;
        private final List<LogSink> sinks;
        private final int batchSize;
        private final long flushIntervalMs;
        private final Thread worker;
        private final AtomicBoolean running = new AtomicBoolean(true);

        AsyncDispatcher(List<LogSink> sinks,
                        int batchSize,
                        int queueCapacity,
                        long flushIntervalMs) {
            this.sinks = sinks;
            this.batchSize = batchSize;
            this.flushIntervalMs = flushIntervalMs;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.worker = new Thread(this::run, "logger-dispatcher");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        void enqueue(LogEvent e, BackPressurePolicy policy) {
            switch (policy) {
                case BLOCK -> {
                    try { queue.put(e); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                case DROP -> queue.offer(e);   // silently drop
            }
        }

        private void run() {
            List<LogEvent> batch = new ArrayList<>(batchSize);
            while (running.get() || !queue.isEmpty()) {
                try {
                    LogEvent first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                    if (first != null) batch.add(first);
                    queue.drainTo(batch, batchSize - batch.size());

                    if (!batch.isEmpty()) {
                        for (LogSink s : sinks) {
                            try { s.writeBatch(batch); } catch (Exception ex) { ex.printStackTrace(); }
                        }
                        batch.clear();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override public void close() {
            running.set(false);
            worker.interrupt();
            try { worker.join(); } catch (InterruptedException ignored) {}
            sinks.forEach(s -> { try { s.close(); } catch (Exception ignore) {} });
        }
    }

    /* ─────────────────────────────  API  ───────────────────────────────── */

    /** Facade handed to user code; nearly no sync cost. */
    static final class Logger {
        private final String name;
        private final AsyncDispatcher dispatcher;
        private final BackPressurePolicy bp;

        Logger(String name, AsyncDispatcher dispatcher, BackPressurePolicy bp) {
            this.name = name;  this.dispatcher = dispatcher;  this.bp = bp;
        }

        // convenience helpers
        public void info (String m) { log(LogLevel.INFO , m); }
        public void debug(String m) { log(LogLevel.DEBUG, m); }
        public void warn (String m) { log(LogLevel.WARN , m); }
        public void error(String m) { log(LogLevel.ERROR, m); }
        public void trace(String m) { log(LogLevel.TRACE, m); }

        public void log(LogLevel level, String message) {
            LogEvent e = new LogEvent(
                    Instant.now(), level,
                    Thread.currentThread().getName(), name, message, Map.of()
            );
            dispatcher.enqueue(e, bp);
        }
    }

    /** Bootstrap / factory. */
    static final class LogManager implements AutoCloseable {
        private static LogManager INSTANCE;

        public static synchronized LogManager initDefault() {
            if (INSTANCE == null) {
                try {
                    LogSink console = new ConsoleSink();
                    LogSink file    = new FileSink(Paths.get("logs"), 10 * 1024 * 1024);
                    INSTANCE = new LogManager(
                            List.of(console, file),
                            128, 10_000, 1_000,
                            BackPressurePolicy.BLOCK
                    );
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            return INSTANCE;
        }

        public static Logger getLogger(Class<?> cls) {
            if (INSTANCE == null) throw new IllegalStateException("LogManager not initialised");
            return INSTANCE.logger(cls.getName());
        }
        public static Logger getLogger(String name) {
            if (INSTANCE == null) throw new IllegalStateException("LogManager not initialised");
            return INSTANCE.logger(name);
        }

        // internal wiring
        private final AsyncDispatcher dispatcher;
        private final BackPressurePolicy bp;

        private LogManager(List<LogSink> sinks,
                           int batchSize,
                           int queueCapacity,
                           long flushIntervalMs,
                           BackPressurePolicy bp) {
            this.dispatcher = new AsyncDispatcher(sinks, batchSize, queueCapacity, flushIntervalMs);
            this.bp = bp;
        }

        private Logger logger(String name) { return new Logger(name, dispatcher, bp); }
        @Override public void close() { dispatcher.close(); }
    }

    /* ───────────────────────────  DEMO MAIN  ───────────────────────────── */

    public static void main(String[] args) throws Exception {
        try (LogManager lm = LogManager.initDefault()) {
            Logger log = LogManager.getLogger(LoggerDemo.class);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            for (int i = 0; i < 4; i++) {
                int id = i;
                pool.submit(() -> {
                    Logger tlog = LogManager.getLogger("worker-" + id);
                    for (int j = 0; j < 1_000; j++) {
                        tlog.info("worker-" + id + " message " + j);
                    }
                });
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            log.info("All work done.  Check console + logs/ for output.");
        }
    }
}

