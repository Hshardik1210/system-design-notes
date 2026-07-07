// PUB-SUB / MESSAGE QUEUE — topics, subscribers, consumer groups (C++17)
//
// PUSH (Observer): subscriber callbacks are invoked on publish (fan-out).
// PULL (Kafka-style): append-only log + per-consumer-group offset; groups read
// the same log independently.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <functional>
using namespace std;

// A single named channel: the append-only log of all messages (for PULL) plus
// the list of push callbacks (Observer subscribers notified on publish).
struct Topic {
    string name;
    vector<string> log;                          // append-only
    vector<function<void(const string&)>> subscribers; // push callbacks
};

// The central message hub: owns all topics and tracks each consumer group's
// read position (offset), so groups can read the same log independently.
class Broker {
    unordered_map<string, Topic> topics;
    // topic -> (group -> next offset)
    unordered_map<string, unordered_map<string, int>> groupOffsets;
public:
    // Register a topic (idempotent: the count() guard skips if it exists).
    void createTopic(const string& name) {
        if (!topics.count(name)) { topics[name].name = name; groupOffsets[name]; }
    }
    // PUSH subscription (Observer): store a callback invoked on every publish.
    void subscribe(const string& topic, function<void(const string&)> handler) {
        topics[topic].subscribers.push_back(move(handler));
    }
    // Publish: append to log (for PULL) and immediately notify subscribers (PUSH).
    void publish(const string& topic, const string& message) {
        auto& t = topics[topic];
        t.log.push_back(message);
        // Fan-out: deliver the same message to every registered subscriber.
        for (auto& s : t.subscribers) s(message); // fan-out
    }
    // PULL: return only messages this group hasn't read yet; each group keeps
    // its own offset so groups don't interfere with one another.
    vector<string> poll(const string& topic, const string& group) {
        auto& t = topics[topic];
        auto& offsets = groupOffsets[topic];
        // Where this group left off last time (0 = never read before).
        int from = offsets.count(group) ? offsets[group] : 0;
        // Slice out the unread tail of the log as this poll's batch.
        vector<string> batch(t.log.begin() + from, t.log.end());
        offsets[group] = (int)t.log.size(); // commit
        return batch;
    }
};

// Small helper to render a vector of strings as "[a, b, c]" for printing.
static string join(const vector<string>& v) {
    string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += v[i] + (i + 1 < v.size() ? ", " : "");
    return s + "]";
}

// Demo/driver: shows PUSH fan-out on publish, then two independent consumer
// groups PULLing the same log via their own offsets.
int main() {
    Broker broker;
    broker.createTopic("orders");

    broker.subscribe("orders", [](const string& m) { cout << "  [push:analytics] " << m << "\n"; });
    broker.subscribe("orders", [](const string& m) { cout << "  [push:audit]     " << m << "\n"; });

    cout << "--- publish 3 messages (push delivery) ---\n";
    broker.publish("orders", "order#1 placed");
    broker.publish("orders", "order#2 placed");
    broker.publish("orders", "order#3 placed");

    cout << "--- pull by two independent consumer groups ---\n";
    cout << "groupA poll: " << join(broker.poll("orders", "groupA")) << "\n";
    cout << "groupB poll: " << join(broker.poll("orders", "groupB")) << "\n";

    broker.publish("orders", "order#4 placed");
    cout << "groupA poll: " << join(broker.poll("orders", "groupA")) << "\n"; // only #4
    cout << "groupA poll: " << join(broker.poll("orders", "groupA")) << "\n"; // empty
    return 0;
}
