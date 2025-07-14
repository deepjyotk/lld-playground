package com.lld_playground.questions.log_aggregator;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/*
* Remember the message format:  we will log like: 1626148800  INFO [main] version 2 demo starting
* where 1626148800 is the epoch time in milliseconds, INFO is the log level, main is the logger name, and version 2 demo starting is the message.
 * Algorithm:
 * 1. entiites: enum Level, record Even(epochMillis, level, logger, msg){}, interface sink{write(Event)}, consoleSink - Sink, fileSink - Sink
 * 2. logger-AutoCloseable(name, list of sinks), methods: info, warn, error, and a log method which 
 * creates a new event and puts the event to all the sinks by iterating over the list of sinks
 *
 */

public class L02_MiniLoggerDemo {

    /* ─────────── 1. Log levels ─────────── */
    enum Level { DEBUG, INFO, WARN, ERROR }

    /* ─────────── 2. Value object ───────── */
    record Event(long epochMillis, Level level, String logger, String msg) { }

    /* ─────────── 3. Sink interface ─────── */
    interface Sink { void write(Event e); }

    /* ─────────── 4a. Console sink ──────── */
    static final class ConsoleSink implements Sink {
        @Override public void write(Event e) {
            System.out.printf("%d  %-5s [%s] %s%n",
                    e.epochMillis(), e.level(), e.logger(), e.msg());
        }
    }

    /*
     * FileSink implements Sink and AutoCloseable
     * 1. in the constructor it receives the FileName(where to write the logs).
     * 2. Since it implements Sink, it implements write(event e) method.
     * 3. in the constructor open the file and create a BufferedWriter to write to the file.
     * 4. in the write method, write the event to the file.
     * 5. in the close method, close the BufferedWriter.
     */
    /*
     * What's the benefit of using a BufferedWriter?
     * 1. buffered char output stream writer, instead of writing char by char, it writes in chunks.
     * 2. it's more efficient to write in chunks since disk io is an expensive operation.
     * 3. when flush is called it writes to the file.
     */
    static final class FileSink implements Sink, AutoCloseable {
        private final java.io.BufferedWriter out;
        FileSink(String file) {
            try {
                out = Files.newBufferedWriter(
                        Paths.get(file),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        }
        
        @Override public void write(Event e) {
            try {
                out.write(String.format("%d  %-5s [%s] %s%n",
                        e.epochMillis(), e.level(), e.logger(), e.msg()));
                out.flush();
            } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        }

        /*
         * why to close?
         *  we have resource limit from OS, let's say in for loop we open 1000 files, we will
         *  run out of file descriptors since OS will not allow us to open more files.
         *  so we need to close the file after we are done with it.
         * 
         */
        @Override public void close() throws Exception { out.close(); }
    }

    /*
     * 
     */
    static final class Logger implements AutoCloseable {
        private final String name;
        private final java.util.List<Sink> sinks;   // fan-out

        Logger(String name, java.util.List<Sink> sinks) {
            this.name = name;  this.sinks = sinks;
        }

        /* helpers */
        public void info (String m) { log(Level.INFO , m); }
        public void warn (String m) { log(Level.WARN , m); }
        public void error(String m) { log(Level.ERROR, m); }

        public void log(Level lvl, String msg) {
            Event e = new Event(System.currentTimeMillis(), lvl, name, msg);
            sinks.forEach(s -> s.write(e));          // synchronous fan-out
        }

        /* pass-through close so demo can shut file sink */
        @Override public void close() throws Exception {
            for (Sink s : sinks)
                if (s instanceof AutoCloseable ac) ac.close();
        }
    }

    /* ─────────── 6. Tiny factory helper ─── */
    static Logger newDefaultLogger(String name) {
        Sink console = new ConsoleSink();
        Sink file    = new FileSink("app.log");   // append to ./app.log
        return new Logger(name, java.util.List.of(console, file));
    }

    /* ─────────── 7. Demo ─────────────────── */
    public static void main(String[] args) throws Exception {

        //* create a logger with try-with-resources, so it will be closed automatically
        try (Logger log = newDefaultLogger("main")) {
            log.info("version 2 demo starting");

            
            Logger workerLog = newDefaultLogger("worker");

            for (int i = 0; i < 5; i++) {
                workerLog.info("task " + i + " done");
            }

            log.info("All work finished 🎉");
        }   // try-with-resources flushes & closes FileSink
    }
}
