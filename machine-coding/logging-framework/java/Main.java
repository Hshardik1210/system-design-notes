import java.util.*;

/**
 * LOGGING FRAMEWORK.
 *
 * Two patterns combine:
 *   - Chain of Responsibility: log handlers are chained by minimum level
 *     (INFO -> WARN -> ERROR). A message flows down the chain; each handler that
 *     is enabled for the message's level writes it and passes it along.
 *   - Strategy: WHERE a handler writes (console / file / etc.) is an Appender,
 *     swappable independently of the level logic.
 *
 * This is essentially how log4j / SLF4J are structured.
 *
 * Key classes:
 *   - Level        : the severity of a message (INFO < WARN < ERROR).
 *   - Appender      : Strategy interface for WHERE a line is written (console/file).
 *   - LogHandler    : one link in the Chain of Responsibility (has a min level + appender).
 *   - DefaultHandler: a concrete handler that formats "LEVEL message".
 *   - Logger        : Facade with simple info()/warn()/error() methods.
 */
public class Main {

    // Severity levels. Each carries a numeric weight so we can compare them
    // with >= (e.g. ERROR is more severe than INFO). Higher number = more severe.
    enum Level {
        INFO(1), WARN(2), ERROR(3);
        final int severity;
        Level(int s) { severity = s; }
    }

    // ---------- Strategy: appender (the sink) ----------
    // Strategy interface: an appender only knows HOW/WHERE to write a finished line.
    // Swapping the sink (console, file, network...) never touches the level logic.
    interface Appender {
        void append(String formatted);
    }
    // Sink that prints to the terminal.
    static class ConsoleAppender implements Appender {
        public void append(String s) { System.out.println("[CONSOLE] " + s); }
    }
    // Pretend file appender: just buffers lines (so the demo stays self-contained).
    // Real code would write to disk here; we keep them in a list to inspect later.
    static class InMemoryFileAppender implements Appender {
        final List<String> lines = new ArrayList<>();
        public void append(String s) { lines.add(s); System.out.println("[FILE] " + s); }
    }

    // ---------- Chain of Responsibility: level handlers ----------
    // One link in the chain. It has a minimum level, an appender to write with,
    // and a reference to the next link. A message travels through every link.
    static abstract class LogHandler {
        private final Level level;      // minimum level this handler acts on
        private final Appender appender;
        private LogHandler next;        // next link in the chain

        LogHandler(Level level, Appender appender) { this.level = level; this.appender = appender; }

        // Links this handler to the next one and returns that next handler so
        // calls can be chained fluently (a.setNext(b).setNext(c)).
        LogHandler setNext(LogHandler next) { this.next = next; return next; } // fluent chaining

        // Core chain step: maybe write the message here, then always forward it on.
        void log(Level msgLevel, String message) {
            // Level filter: write only if the message is at least as severe as
            // this handler's threshold (e.g. an ERROR-only handler ignores INFO).
            if (msgLevel.severity >= level.severity) {
                appender.append(format(msgLevel, message));
            }
            // Chain traversal: hand the same message to the next link (if any),
            // so multiple handlers can each react to it.
            if (next != null) next.log(msgLevel, message); // pass down the chain
        }

        // Subclasses can customise the formatting.
        abstract String format(Level level, String message);
    }

    // Concrete handler using a plain "LEVEL message" text format.
    static class DefaultHandler extends LogHandler {
        DefaultHandler(Level level, Appender appender) { super(level, appender); }
        String format(Level level, String message) {
            return level + " " + message;
        }
    }

    // ---------- Facade the app talks to ----------
    // Facade: hides the chain behind friendly info()/warn()/error() calls so
    // callers never deal with Level or handlers directly.
    static class Logger {
        private final LogHandler chain;
        Logger(LogHandler chain) { this.chain = chain; }
        void info(String m)  { chain.log(Level.INFO, m); }
        void warn(String m)  { chain.log(Level.WARN, m); }
        void error(String m) { chain.log(Level.ERROR, m); }
    }

    // Wires up the chain and runs a small demo.
    public static void main(String[] args) {
        // Build a chain:
        //   INFO+  -> console (everything)
        //   ERROR+ -> file    (only errors persisted)
        InMemoryFileAppender file = new InMemoryFileAppender();
        LogHandler consoleHandler = new DefaultHandler(Level.INFO, new ConsoleAppender());
        // Second link only reacts to ERROR+, so it acts as the "errors go to file" rule.
        consoleHandler.setNext(new DefaultHandler(Level.ERROR, file));

        // The app only holds the Logger facade and the head of the chain.
        Logger log = new Logger(consoleHandler);

        log.info("service started");        // console only
        log.warn("high memory usage");      // console only
        log.error("db connection failed");  // console + file

        System.out.println("\nPersisted to file: " + file.lines);
    }
}
