// LOGGING FRAMEWORK (C++17)
//
// Chain of Responsibility: handlers chained by minimum level; a message flows
// down and each enabled handler writes it. Strategy: Appender decides WHERE to write.
// (This mirrors log4j / SLF4J structure.)

#include <iostream>
#include <string>
#include <vector>
#include <memory>
using namespace std;

enum class Level { INFO = 1, WARN = 2, ERROR = 3 };
static string levelStr(Level l) {
    return l == Level::INFO ? "INFO" : l == Level::WARN ? "WARN" : "ERROR";
}

// ---------- Strategy: appender ----------
struct Appender {
    virtual ~Appender() = default;
    virtual void append(const string& formatted) = 0;
};
struct ConsoleAppender : Appender {
    void append(const string& s) override { cout << "[CONSOLE] " << s << "\n"; }
};
struct InMemoryFileAppender : Appender {
    vector<string> lines;
    void append(const string& s) override { lines.push_back(s); cout << "[FILE] " << s << "\n"; }
};

// ---------- Chain of Responsibility ----------
class LogHandler {
    Level level;
    shared_ptr<Appender> appender;
    shared_ptr<LogHandler> next;
public:
    LogHandler(Level lvl, shared_ptr<Appender> ap) : level(lvl), appender(move(ap)) {}
    shared_ptr<LogHandler> setNext(shared_ptr<LogHandler> n) { next = n; return n; }

    void log(Level msgLevel, const string& message) {
        if (static_cast<int>(msgLevel) >= static_cast<int>(level))
            appender->append(levelStr(msgLevel) + " " + message);
        if (next) next->log(msgLevel, message);
    }
};

// ---------- Facade ----------
class Logger {
    shared_ptr<LogHandler> chain;
public:
    explicit Logger(shared_ptr<LogHandler> c) : chain(move(c)) {}
    void info(const string& m)  { chain->log(Level::INFO, m); }
    void warn(const string& m)  { chain->log(Level::WARN, m); }
    void error(const string& m) { chain->log(Level::ERROR, m); }
};

int main() {
    auto file = make_shared<InMemoryFileAppender>();
    auto consoleHandler = make_shared<LogHandler>(Level::INFO, make_shared<ConsoleAppender>());
    consoleHandler->setNext(make_shared<LogHandler>(Level::ERROR, file)); // ERROR+ -> file

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
