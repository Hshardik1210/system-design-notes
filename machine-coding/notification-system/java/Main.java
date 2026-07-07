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
    // Strategy pattern: this interface is the common contract every delivery
    // channel must follow. Because they all look the same to the dispatcher,
    // channels are interchangeable and you can add new ones (WhatsApp, Slack)
    // just by writing a new class - no changes needed elsewhere.
    interface Channel {
        // Short identifier for the channel, e.g. "EMAIL" / "SMS" / "PUSH".
        String name();
        // Returns true on success; may fail transiently (we retry).
        boolean send(String to, String message);
    }
    // Concrete strategy: always succeeds. Stands in for a real email provider.
    static class EmailChannel implements Channel {
        public String name() { return "EMAIL"; }
        public boolean send(String to, String msg) { System.out.println("  ✉  email -> " + to + ": " + msg); return true; }
    }
    // Concrete strategy: fails on its first attempt to demonstrate the retry logic.
    static class SmsChannel implements Channel {
        private int attempts = 0; // counts how many times send() has been called
        public String name() { return "SMS"; }
        public boolean send(String to, String msg) {
            // Simulate a transient failure: the very first attempt fails,
            // the retry (attempt 2) then succeeds. This is how flaky networks behave.
            if (++attempts == 1) { System.out.println("  ✗  sms -> " + to + " FAILED (transient)"); return false; }
            System.out.println("  📱 sms -> " + to + ": " + msg); return true;
        }
    }
    // Concrete strategy: always succeeds. Stands in for a mobile push service.
    static class PushChannel implements Channel {
        public String name() { return "PUSH"; }
        public boolean send(String to, String msg) { System.out.println("  🔔 push -> " + to + ": " + msg); return true; }
    }

    // ---------- Template rendering ----------
    // Holds a message pattern with {placeholders} and fills them in at send time.
    // Keeping the wording separate from the delivery code means marketing can
    // change the text without touching the channels.
    static class Template {
        private final String pattern; // e.g. "Hi {name}, your order {orderId} shipped"
        Template(String pattern) { this.pattern = pattern; }
        // Replace every {key} in the pattern with its value, returning the final text.
        String render(Map<String, String> vars) {
            String out = pattern;
            // Walk each variable and swap "{key}" for its value in the text.
            for (Map.Entry<String, String> e : vars.entrySet())
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            return out;
        }
    }

    // Represents a recipient: which channels they opted into and their address on each.
    static class User {
        final String id;
        final Map<String, String> addresses = new HashMap<>(); // channelName -> address
        final Set<String> subscribed = new LinkedHashSet<>();  // channels opted into (order preserved)
        User(String id) { this.id = id; }
        // Opt this user into a channel and record their address for it.
        // Returns "this" so calls can be chained (fluent builder style).
        User subscribe(String channel, String address) { subscribed.add(channel); addresses.put(channel, address); return this; }
    }

    // ---------- Dispatcher (Observer subject) ----------
    // The central hub: it knows all available channels and, for one notification,
    // fans it out to every channel the user subscribed to (the Observer idea).
    static class NotificationService {
        private final Map<String, Channel> channels = new HashMap<>(); // name -> channel
        private final int maxRetries;                                  // attempts allowed per channel
        NotificationService(int maxRetries) { this.maxRetries = maxRetries; }

        // Make a channel available for use, keyed by its name.
        void registerChannel(Channel c) { channels.put(c.name(), c); }

        // Fan a rendered message out to each subscribed channel, with retries.
        void notify(User user, Template template, Map<String, String> vars) {
            String message = template.render(vars); // build the final text once, reuse for all channels
            System.out.println("Notifying " + user.id + ": \"" + message + "\"");
            // Loop over the channels this user opted into and send to each.
            for (String chName : user.subscribed) {
                Channel channel = channels.get(chName);
                if (channel == null) continue; // user asked for a channel we don't support; skip it
                deliverWithRetry(channel, user.addresses.get(chName), message);
            }
        }

        // Try sending on one channel, retrying up to maxRetries times on failure.
        private void deliverWithRetry(Channel channel, String to, String message) {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                if (channel.send(to, message)) return;         // success: stop retrying immediately
                if (attempt < maxRetries)
                    System.out.println("     retry " + attempt + "/" + (maxRetries - 1) + " on " + channel.name());
            }
            // Every attempt failed. A real system would move this to a dead-letter queue (DLQ).
            System.out.println("     ! giving up on " + channel.name() + " after " + maxRetries + " attempts");
        }
    }

    // Entry point: wires everything together and runs one demo notification.
    public static void main(String[] args) {
        // Create the dispatcher allowing up to 3 attempts per channel.
        NotificationService svc = new NotificationService(3);
        // Register the channels the system can deliver through.
        svc.registerChannel(new EmailChannel());
        svc.registerChannel(new SmsChannel());
        svc.registerChannel(new PushChannel());

        // Build a user and chain subscriptions (each returns the same user).
        User alice = new User("alice")
                .subscribe("EMAIL", "alice@x.com")
                .subscribe("SMS", "+91-99999")
                .subscribe("PUSH", "device-tok-1");

        // Define the message template and the values that fill its placeholders.
        Template t = new Template("Hi {name}, your order {orderId} has shipped!");
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "Alice"); vars.put("orderId", "ORD-42");

        svc.notify(alice, t, vars); // SMS fails once then retries successfully
    }
}
