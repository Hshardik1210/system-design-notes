// LOGGING FRAMEWORK (C++17)
//
// Chain of Responsibility: handlers chained by minimum level; a message flows
// down and each enabled handler writes it. Strategy: Appender decides WHERE to write.
// (This mirrors log4j / SLF4J structure.)
//
// Key pieces:
//   - Level        : severity of a message (INFO < WARN < ERROR).
//   - Appender      : Strategy base class for WHERE a line goes (console/file).
//   - LogHandler    : one link in the chain (holds a min level + an appender).
//   - Logger        : Facade exposing simple info()/warn()/error().

#include <iostream>
#include <string>
#include <vector>
#include <memory>
using namespace std;

// Severity levels. The numeric values let us compare with >= so we can tell
// that ERROR (3) is more severe than INFO (1).
enum class Level { INFO = 1, WARN = 2, ERROR = 3 };
// Turns a Level enum into its printable text.
static string levelStr(Level l) {
    return l == Level::INFO ? "INFO" : l == Level::WARN ? "WARN" : "ERROR";
}

// ---------- Strategy: appender ----------
// Strategy base class: an appender only knows HOW/WHERE to output a finished
// line. Swapping the sink never affects the level-routing logic.
struct Appender {
    virtual ~Appender() = default;
    virtual void append(const string& formatted) = 0;
};
// Sink that writes to the terminal.
struct ConsoleAppender : Appender {
    void append(const string& s) override { cout << "[CONSOLE] " << s << "\n"; }
};
// Pretend file sink: buffers lines in a vector so the demo stays self-contained.
struct InMemoryFileAppender : Appender {
    vector<string> lines;
    void append(const string& s) override { lines.push_back(s); cout << "[FILE] " << s << "\n"; }
};

// ---------- Chain of Responsibility ----------
// One link in the chain: a minimum level, an appender to write with, and a
// pointer to the next link. A message is passed through every link in turn.
class LogHandler {
    Level level;
    shared_ptr<Appender> appender;
    shared_ptr<LogHandler> next;
public:
    LogHandler(Level lvl, shared_ptr<Appender> ap) : level(lvl), appender(move(ap)) {}
    // Links to the next handler and returns it so calls can be chained fluently.
    shared_ptr<LogHandler> setNext(shared_ptr<LogHandler> n) { next = n; return n; }

    // Core chain step: maybe write here, then always forward to the next link.
    void log(Level msgLevel, const string& message) {
        // Level filter: write only if the message meets this handler's threshold.
        if (static_cast<int>(msgLevel) >= static_cast<int>(level))
            appender->append(levelStr(msgLevel) + " " + message);
        // Chain traversal: hand the same message to the next link, if present.
        if (next) next->log(msgLevel, message);
    }
};

// ---------- Facade ----------
// Facade: hides the handler chain behind friendly info()/warn()/error() calls.
class Logger {
    shared_ptr<LogHandler> chain;
public:
    explicit Logger(shared_ptr<LogHandler> c) : chain(move(c)) {}
    void info(const string& m)  { chain->log(Level::INFO, m); }
    void warn(const string& m)  { chain->log(Level::WARN, m); }
    void error(const string& m) { chain->log(Level::ERROR, m); }
};

int main() {
    // Build a chain: INFO+ -> console (everything), ERROR+ -> file (errors only).
    auto file = make_shared<InMemoryFileAppender>();
    auto consoleHandler = make_shared<LogHandler>(Level::INFO, make_shared<ConsoleAppender>());
    consoleHandler->setNext(make_shared<LogHandler>(Level::ERROR, file)); // ERROR+ -> file

    // App only touches the Logger facade (its head is the first chain link).
    Logger log(consoleHandler);
    log.info("service started");       // console only
    log.warn("high memory usage");     // console only
    log.error("db connection failed"); // console + file

    cout << "\nPersisted to file: [";
    for (size_t i = 0; i < file->lines.size(); ++i)
        cout << file->lines[i] << (i + 1 < file->lines.size() ? ", " : "");
    cout << "]\n";
    return 0;
}
