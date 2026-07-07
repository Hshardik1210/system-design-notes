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
 */
public class Main {

    enum Level {
        INFO(1), WARN(2), ERROR(3);
        final int severity;
        Level(int s) { severity = s; }
    }

    // ---------- Strategy: appender (the sink) ----------
    interface Appender {
        void append(String formatted);
    }
    static class ConsoleAppender implements Appender {
        public void append(String s) { System.out.println("[CONSOLE] " + s); }
    }
    // Pretend file appender: just buffers lines (so the demo stays self-contained).
    static class InMemoryFileAppender implements Appender {
        final List<String> lines = new ArrayList<>();
        public void append(String s) { lines.add(s); System.out.println("[FILE] " + s); }
    }

    // ---------- Chain of Responsibility: level handlers ----------
    static abstract class LogHandler {
        private final Level level;      // minimum level this handler acts on
        private final Appender appender;
        private LogHandler next;        // next link in the chain

        LogHandler(Level level, Appender appender) { this.level = level; this.appender = appender; }

        LogHandler setNext(LogHandler next) { this.next = next; return next; } // fluent chaining

        void log(Level msgLevel, String message) {
            // Handle if the message is at least as severe as this handler's level.
            if (msgLevel.severity >= level.severity) {
                appender.append(format(msgLevel, message));
            }
            if (next != null) next.log(msgLevel, message); // pass down the chain
        }

        // Subclasses can customise the formatting.
        abstract String format(Level level, String message);
    }

    static class DefaultHandler extends LogHandler {
        DefaultHandler(Level level, Appender appender) { super(level, appender); }
        String format(Level level, String message) {
            return level + " " + message;
        }
    }

    // ---------- Facade the app talks to ----------
    static class Logger {
        private final LogHandler chain;
        Logger(LogHandler chain) { this.chain = chain; }
        void info(String m)  { chain.log(Level.INFO, m); }
        void warn(String m)  { chain.log(Level.WARN, m); }
        void error(String m) { chain.log(Level.ERROR, m); }
    }

    public static void main(String[] args) {
        // Build a chain:
        //   INFO+  -> console (everything)
        //   ERROR+ -> file    (only errors persisted)
        InMemoryFileAppender file = new InMemoryFileAppender();
        LogHandler consoleHandler = new DefaultHandler(Level.INFO, new ConsoleAppender());
        consoleHandler.setNext(new DefaultHandler(Level.ERROR, file));

        Logger log = new Logger(consoleHandler);

        log.info("service started");        // console only
        log.warn("high memory usage");      // console only
        log.error("db connection failed");  // console + file

        System.out.println("\nPersisted to file: " + file.lines);
    }
}
