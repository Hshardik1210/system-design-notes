import java.util.*;
import java.util.function.Consumer;

/**
 * PUB-SUB / MESSAGE QUEUE — topics, subscribers, and consumer groups.
 *
 * Two delivery models shown:
 *   - PUSH (Observer): a subscriber registers a callback on a topic; publish()
 *     fans the message out to every subscriber immediately.
 *   - PULL with offsets (Kafka-style): each topic keeps an append-only log; a
 *     consumer group tracks its own read offset and polls new messages. Different
 *     groups read the same log independently (broadcast across groups).
 *
 * Pattern: Observer for push; commit-log + per-group offset for pull.
 */
public class Main {

    static class Topic {
        final String name;
        final List<String> log = new ArrayList<>();        // append-only message log
        final List<Consumer<String>> subscribers = new ArrayList<>(); // push callbacks
        Topic(String name) { this.name = name; }
    }

    static class Broker {
        private final Map<String, Topic> topics = new HashMap<>();
        // topicName -> (groupName -> next offset to read)
        private final Map<String, Map<String, Integer>> groupOffsets = new HashMap<>();

        void createTopic(String name) {
            topics.putIfAbsent(name, new Topic(name));
            groupOffsets.putIfAbsent(name, new HashMap<>());
        }

        // PUSH subscription (Observer).
        void subscribe(String topic, Consumer<String> handler) {
            topics.get(topic).subscribers.add(handler);
        }

        // Publish: append to log AND push to live subscribers.
        void publish(String topic, String message) {
            Topic t = topics.get(topic);
            t.log.add(message);
            for (Consumer<String> s : t.subscribers) s.accept(message); // fan-out
        }

        // PULL: a consumer group reads messages it hasn't seen yet.
        List<String> poll(String topic, String group) {
            Topic t = topics.get(topic);
            Map<String, Integer> offsets = groupOffsets.get(topic);
            int from = offsets.getOrDefault(group, 0);
            List<String> batch = new ArrayList<>(t.log.subList(from, t.log.size()));
            offsets.put(group, t.log.size()); // commit new offset
            return batch;
        }
    }

    public static void main(String[] args) {
        Broker broker = new Broker();
        broker.createTopic("orders");

        // Two push subscribers (e.g., analytics + audit).
        broker.subscribe("orders", m -> System.out.println("  [push:analytics] " + m));
        broker.subscribe("orders", m -> System.out.println("  [push:audit]     " + m));

        System.out.println("--- publish 3 messages (push delivery) ---");
        broker.publish("orders", "order#1 placed");
        broker.publish("orders", "order#2 placed");
        broker.publish("orders", "order#3 placed");

        System.out.println("--- pull by two independent consumer groups ---");
        System.out.println("groupA poll: " + broker.poll("orders", "groupA")); // all 3
        System.out.println("groupB poll: " + broker.poll("orders", "groupB")); // all 3 (independent)

        broker.publish("orders", "order#4 placed");
        System.out.println("groupA poll: " + broker.poll("orders", "groupA")); // only #4
        System.out.println("groupA poll: " + broker.poll("orders", "groupA")); // empty (caught up)
    }
}
