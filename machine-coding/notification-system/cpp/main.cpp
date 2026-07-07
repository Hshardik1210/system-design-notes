// NOTIFICATION SYSTEM — multi-channel delivery with retries (C++17)
//
// Strategy: each Channel is an interchangeable sender.
// Observer:  a user subscribes to channels; dispatcher fans out to each.
// Template:  render message from pattern + vars. Retry on transient failure.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <set>
#include <memory>
using namespace std;

// ---------- Strategy: channel ----------
struct Channel {
    virtual ~Channel() = default;
    virtual string name() const = 0;
    virtual bool send(const string& to, const string& msg) = 0;
};
struct EmailChannel : Channel {
    string name() const override { return "EMAIL"; }
    bool send(const string& to, const string& msg) override { cout << "  email -> " << to << ": " << msg << "\n"; return true; }
};
struct SmsChannel : Channel {
    int attempts = 0;
    string name() const override { return "SMS"; }
    bool send(const string& to, const string& msg) override {
        if (++attempts == 1) { cout << "  sms -> " << to << " FAILED (transient)\n"; return false; }
        cout << "  sms -> " << to << ": " << msg << "\n"; return true;
    }
};
struct PushChannel : Channel {
    string name() const override { return "PUSH"; }
    bool send(const string& to, const string& msg) override { cout << "  push -> " << to << ": " << msg << "\n"; return true; }
};

// ---------- Template ----------
struct Template {
    string pattern;
    explicit Template(string p) : pattern(move(p)) {}
    string render(const map<string, string>& vars) const {
        string out = pattern;
        for (auto& [k, v] : vars) {
            string token = "{" + k + "}";
            size_t pos;
            while ((pos = out.find(token)) != string::npos) out.replace(pos, token.size(), v);
        }
        return out;
    }
};

struct User {
    string id;
    unordered_map<string, string> addresses; // channel -> address
    vector<string> subscribed;                // channels (ordered)
    explicit User(string i) : id(move(i)) {}
    User& subscribe(const string& channel, const string& address) {
        subscribed.push_back(channel); addresses[channel] = address; return *this;
    }
};

class NotificationService {
    unordered_map<string, shared_ptr<Channel>> channels;
    int maxRetries;

    void deliverWithRetry(Channel& ch, const string& to, const string& msg) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (ch.send(to, msg)) return;
            if (attempt < maxRetries)
                cout << "     retry " << attempt << "/" << (maxRetries - 1) << " on " << ch.name() << "\n";
        }
        cout << "     ! giving up on " << ch.name() << " after " << maxRetries << " attempts\n";
    }
public:
    explicit NotificationService(int r) : maxRetries(r) {}
    void registerChannel(shared_ptr<Channel> c) { channels[c->name()] = move(c); }

    void notify(User& user, const Template& tmpl, const map<string, string>& vars) {
        string msg = tmpl.render(vars);
        cout << "Notifying " << user.id << ": \"" << msg << "\"\n";
        for (auto& chName : user.subscribed) {
            auto it = channels.find(chName);
            if (it == channels.end()) continue;
            deliverWithRetry(*it->second, user.addresses[chName], msg);
        }
    }
};

int main() {
    NotificationService svc(3);
    svc.registerChannel(make_shared<EmailChannel>());
    svc.registerChannel(make_shared<SmsChannel>());
    svc.registerChannel(make_shared<PushChannel>());

    User alice("alice");
    alice.subscribe("EMAIL", "alice@x.com").subscribe("SMS", "+91-99999").subscribe("PUSH", "device-tok-1");

    Template t("Hi {name}, your order {orderId} has shipped!");
    map<string, string> vars{{"name", "Alice"}, {"orderId", "ORD-42"}};

    svc.notify(alice, t, vars);
    return 0;
}
