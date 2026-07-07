// NOTIFICATION SYSTEM — multi-channel delivery with retries (C++17)
//
// What this program does: renders one message from a template and sends it to a
// user across every channel they subscribed to (EMAIL/SMS/PUSH), retrying on
// transient failures.
//
// Key classes: Channel (+ EmailChannel/SmsChannel/PushChannel), Template, User,
// and NotificationService (the dispatcher).
//
// Design patterns used:
//   Strategy: each Channel is an interchangeable sender behind one interface.
//   Observer:  a user subscribes to channels; dispatcher fans out to each.
//   Template:  render message from pattern + vars. Retry on transient failure.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <set>
#include <memory>
using namespace std;

// ---------- Strategy: channel ----------
// Strategy pattern: the abstract base every channel implements. The dispatcher
// only depends on this interface, so channels are interchangeable and new ones
// (WhatsApp, Slack) can be added without changing existing code.
struct Channel {
    virtual ~Channel() = default;              // virtual destructor: safe deletion via base pointer
    virtual string name() const = 0;           // channel id, e.g. "EMAIL"
    virtual bool send(const string& to, const string& msg) = 0; // true on success
};
// Concrete strategy: always succeeds. Stands in for a real email provider.
struct EmailChannel : Channel {
    string name() const override { return "EMAIL"; }
    bool send(const string& to, const string& msg) override { cout << "  email -> " << to << ": " << msg << "\n"; return true; }
};
// Concrete strategy: fails on its first attempt to demonstrate the retry logic.
struct SmsChannel : Channel {
    int attempts = 0;                          // counts how many times send() has been called
    string name() const override { return "SMS"; }
    bool send(const string& to, const string& msg) override {
        // Simulate a transient failure: first attempt fails, the retry succeeds.
        if (++attempts == 1) { cout << "  sms -> " << to << " FAILED (transient)\n"; return false; }
        cout << "  sms -> " << to << ": " << msg << "\n"; return true;
    }
};
// Concrete strategy: always succeeds. Stands in for a mobile push service.
struct PushChannel : Channel {
    string name() const override { return "PUSH"; }
    bool send(const string& to, const string& msg) override { cout << "  push -> " << to << ": " << msg << "\n"; return true; }
};

// ---------- Template ----------
// Holds a message pattern with {placeholders} and fills them at send time.
// Separating wording from delivery lets the text change without touching channels.
struct Template {
    string pattern;                              // e.g. "Hi {name}, order {orderId} shipped"
    explicit Template(string p) : pattern(move(p)) {}
    // Replace every {key} in the pattern with its value and return the final text.
    string render(const map<string, string>& vars) const {
        string out = pattern;
        for (auto& [k, v] : vars) {
            string token = "{" + k + "}";
            size_t pos;
            // Replace every occurrence of this token (a variable may appear more than once).
            while ((pos = out.find(token)) != string::npos) out.replace(pos, token.size(), v);
        }
        return out;
    }
};

// Represents a recipient: which channels they opted into and their address on each.
struct User {
    string id;
    unordered_map<string, string> addresses; // channel -> address
    vector<string> subscribed;                // channels (ordered)
    explicit User(string i) : id(move(i)) {}
    // Opt this user into a channel and record their address for it.
    // Returns *this so calls can be chained (fluent builder style).
    User& subscribe(const string& channel, const string& address) {
        subscribed.push_back(channel); addresses[channel] = address; return *this;
    }
};

// The central dispatcher: knows all channels and, for one notification, fans it
// out to every channel the user subscribed to (the Observer idea).
class NotificationService {
    unordered_map<string, shared_ptr<Channel>> channels; // name -> channel
    int maxRetries;                                       // attempts allowed per channel

    // Try sending on one channel, retrying up to maxRetries times on failure.
    void deliverWithRetry(Channel& ch, const string& to, const string& msg) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (ch.send(to, msg)) return;                 // success: stop retrying immediately
            if (attempt < maxRetries)
                cout << "     retry " << attempt << "/" << (maxRetries - 1) << " on " << ch.name() << "\n";
        }
        // Every attempt failed. A real system would move this to a dead-letter queue (DLQ).
        cout << "     ! giving up on " << ch.name() << " after " << maxRetries << " attempts\n";
    }
public:
    explicit NotificationService(int r) : maxRetries(r) {}
    // Make a channel available for use, keyed by its name.
    void registerChannel(shared_ptr<Channel> c) { channels[c->name()] = move(c); }

    // Fan a rendered message out to each subscribed channel, with retries.
    void notify(User& user, const Template& tmpl, const map<string, string>& vars) {
        string msg = tmpl.render(vars);                   // build the final text once, reuse for all channels
        cout << "Notifying " << user.id << ": \"" << msg << "\"\n";
        // Loop over the channels this user opted into and send to each.
        for (auto& chName : user.subscribed) {
            auto it = channels.find(chName);
            if (it == channels.end()) continue;           // user asked for a channel we don't support; skip it
            deliverWithRetry(*it->second, user.addresses[chName], msg);
        }
    }
};

// Entry point: wires everything together and runs one demo notification.
int main() {
    // Create the dispatcher allowing up to 3 attempts per channel.
    NotificationService svc(3);
    // Register the channels the system can deliver through.
    svc.registerChannel(make_shared<EmailChannel>());
    svc.registerChannel(make_shared<SmsChannel>());
    svc.registerChannel(make_shared<PushChannel>());

    // Build a user and chain subscriptions (each returns the same user).
    User alice("alice");
    alice.subscribe("EMAIL", "alice@x.com").subscribe("SMS", "+91-99999").subscribe("PUSH", "device-tok-1");

    // Define the message template and the values that fill its placeholders.
    Template t("Hi {name}, your order {orderId} has shipped!");
    map<string, string> vars{{"name", "Alice"}, {"orderId", "ORD-42"}};

    svc.notify(alice, t, vars);
    return 0;
}
