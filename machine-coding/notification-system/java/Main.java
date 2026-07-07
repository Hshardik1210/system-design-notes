import java.util.*;

/**
 * NOTIFICATION SYSTEM — multi-channel delivery with retries.
 *
 * Patterns:
 *   - Strategy  : each Channel (EMAIL/SMS/PUSH) is an interchangeable sender.
 *   - Observer  : users subscribe to channels; the dispatcher fans a notification
 *                 out to each of the user's subscribed channels.
 *   - Template  : messages are rendered from a template + variables.
 *   - Retry     : failed sends are retried up to N times (simulated failures).
 */
public class Main {

    // ---------- Strategy: a delivery channel ----------
    interface Channel {
        String name();
        // Returns true on success; may fail transiently (we retry).
        boolean send(String to, String message);
    }
    static class EmailChannel implements Channel {
        public String name() { return "EMAIL"; }
        public boolean send(String to, String msg) { System.out.println("  ✉  email -> " + to + ": " + msg); return true; }
    }
    static class SmsChannel implements Channel {
        private int attempts = 0;
        public String name() { return "SMS"; }
        public boolean send(String to, String msg) {
            // Simulate: first attempt fails, retry succeeds.
            if (++attempts == 1) { System.out.println("  ✗  sms -> " + to + " FAILED (transient)"); return false; }
            System.out.println("  📱 sms -> " + to + ": " + msg); return true;
        }
    }
    static class PushChannel implements Channel {
        public String name() { return "PUSH"; }
        public boolean send(String to, String msg) { System.out.println("  🔔 push -> " + to + ": " + msg); return true; }
    }

    // ---------- Template rendering ----------
    static class Template {
        private final String pattern; // e.g. "Hi {name}, your order {orderId} shipped"
        Template(String pattern) { this.pattern = pattern; }
        String render(Map<String, String> vars) {
            String out = pattern;
            for (Map.Entry<String, String> e : vars.entrySet())
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            return out;
        }
    }

    static class User {
        final String id;
        final Map<String, String> addresses = new HashMap<>(); // channelName -> address
        final Set<String> subscribed = new LinkedHashSet<>();  // channels opted into
        User(String id) { this.id = id; }
        User subscribe(String channel, String address) { subscribed.add(channel); addresses.put(channel, address); return this; }
    }

    // ---------- Dispatcher (Observer subject) ----------
    static class NotificationService {
        private final Map<String, Channel> channels = new HashMap<>();
        private final int maxRetries;
        NotificationService(int maxRetries) { this.maxRetries = maxRetries; }

        void registerChannel(Channel c) { channels.put(c.name(), c); }

        // Fan a rendered message out to each subscribed channel, with retries.
        void notify(User user, Template template, Map<String, String> vars) {
            String message = template.render(vars);
            System.out.println("Notifying " + user.id + ": \"" + message + "\"");
            for (String chName : user.subscribed) {
                Channel channel = channels.get(chName);
                if (channel == null) continue;
                deliverWithRetry(channel, user.addresses.get(chName), message);
            }
        }

        private void deliverWithRetry(Channel channel, String to, String message) {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                if (channel.send(to, message)) return;         // success
                if (attempt < maxRetries)
                    System.out.println("     retry " + attempt + "/" + (maxRetries - 1) + " on " + channel.name());
            }
            System.out.println("     ! giving up on " + channel.name() + " after " + maxRetries + " attempts");
        }
    }

    public static void main(String[] args) {
        NotificationService svc = new NotificationService(3);
        svc.registerChannel(new EmailChannel());
        svc.registerChannel(new SmsChannel());
        svc.registerChannel(new PushChannel());

        User alice = new User("alice")
                .subscribe("EMAIL", "alice@x.com")
                .subscribe("SMS", "+91-99999")
                .subscribe("PUSH", "device-tok-1");

        Template t = new Template("Hi {name}, your order {orderId} has shipped!");
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "Alice"); vars.put("orderId", "ORD-42");

        svc.notify(alice, t, vars); // SMS fails once then retries successfully
    }
}
