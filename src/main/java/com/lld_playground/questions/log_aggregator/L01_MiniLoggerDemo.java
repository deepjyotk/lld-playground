package com.lld_playground.questions.log_aggregator;

/** A stripped-down, synchronous logger you can read in two minutes. */
public class L01_MiniLoggerDemo {

    /* ─────────── 1. Log levels ─────────── */
    enum Level { DEBUG, INFO, WARN, ERROR }

    /* ─────────── 2. Value object ───────── */
    record Event(long epochMillis, Level level, String logger, String msg) { }

    /* ─────────── 3. Sink interface ─────── */
    interface Sink {               // no batching, no AutoCloseable yet
        void write(Event e);
    }

    /* ─────────── 4. One concrete sink ──── */
    static final class ConsoleSink implements Sink {
        @Override public void write(Event e) {
            System.out.printf("%d  %-5s [%s] %s%n",
                    e.epochMillis(), e.level(), e.logger(), e.msg());
        }
    }

    /* ─────────── 5. Simple logger ──────── */
    static final class SimpleLogger {
        private final String name;
        private final Sink sink;

        SimpleLogger(String name, Sink sink) {
            this.name = name;
            this.sink = sink;
        }

        /* convenience helpers */
        void info (String m) { log(Level.INFO , m); }
        void warn (String m) { log(Level.WARN , m); }
        void error(String m) { log(Level.ERROR, m); }

        /* one synchronous call */
        void log(Level lvl, String msg) {
            Event e = new Event(System.currentTimeMillis(), lvl, name, msg);
            sink.write(e);                 // direct call → caller blocks until printed
        }
    }

    /* ─────────── 6. Quick demo ─────────── */
    public static void main(String[] args) {
        Sink console = new ConsoleSink();

        SimpleLogger mainLog   = new SimpleLogger("main",   console);
        SimpleLogger workerLog = new SimpleLogger("worker", console);

        mainLog.info("program started");

        // pretend to do some work:
        for (int i = 0; i < 5; i++) {
            workerLog.info("loop " + i);
        }

        mainLog.info("All work done.");
    }
}

