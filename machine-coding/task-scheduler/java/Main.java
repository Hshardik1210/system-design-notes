import java.util.*;
import java.util.concurrent.locks.*;

/**
 * TASK SCHEDULER / CRON — run jobs at/after a scheduled time, support recurring
 * jobs, using a min-heap (priority queue) keyed by next-run time and a worker
 * thread that sleeps precisely until the next job is due.
 *
 * Key mechanics:
 *   - PriorityQueue orders jobs by nextRunMillis (earliest first).
 *   - The worker waits on a Condition until either (a) the head job is due, or
 *     (b) a new earlier job arrives (which signals the condition). This avoids
 *     busy-waiting and reacts immediately to newly scheduled work.
 *   - A recurring job re-enqueues itself with nextRun += interval after running.
 */
public class Main {

    static class Job {
        final String name;
        final Runnable action;
        long nextRunMillis;
        final long intervalMillis; // 0 => one-shot
        Job(String name, Runnable action, long nextRunMillis, long intervalMillis) {
            this.name = name; this.action = action; this.nextRunMillis = nextRunMillis; this.intervalMillis = intervalMillis;
        }
        boolean recurring() { return intervalMillis > 0; }
    }

    static class Scheduler {
        private final PriorityQueue<Job> pq = new PriorityQueue<>(Comparator.comparingLong(j -> j.nextRunMillis));
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition cond = lock.newCondition();
        private volatile boolean running = true;
        private Thread worker;

        void start() {
            worker = new Thread(this::loop, "scheduler");
            worker.start();
        }

        void schedule(Job job) {
            lock.lock();
            try { pq.offer(job); cond.signal(); } // wake worker: a new (maybe earlier) job exists
            finally { lock.unlock(); }
        }

        private void loop() {
            lock.lock();
            try {
                while (running) {
                    if (pq.isEmpty()) { cond.await(); continue; } // nothing to do; wait for work
                    Job head = pq.peek();
                    long now = System.currentTimeMillis();
                    long delay = head.nextRunMillis - now;
                    if (delay > 0) {
                        cond.await(delay, java.util.concurrent.TimeUnit.MILLISECONDS); // sleep until due (or a new job arrives)
                        continue; // re-check head (it may have changed)
                    }
                    pq.poll(); // due -> take it
                    // Run outside the lock so long jobs don't block scheduling.
                    lock.unlock();
                    try { head.action.run(); } finally { lock.lock(); }
                    if (head.recurring() && running) {
                        head.nextRunMillis = System.currentTimeMillis() + head.intervalMillis;
                        pq.offer(head); // reschedule
                    }
                }
            } catch (InterruptedException ignored) {
            } finally { lock.unlock(); }
        }

        void shutdown() {
            lock.lock();
            try { running = false; cond.signalAll(); } finally { lock.unlock(); }
            try { worker.join(); } catch (InterruptedException ignored) {}
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Scheduler s = new Scheduler();
        s.start();
        long t0 = System.currentTimeMillis();

        // One-shot job after 300ms.
        s.schedule(new Job("once-300ms", () ->
                System.out.printf("[%3dms] one-shot fired%n", System.currentTimeMillis() - t0), t0 + 300, 0));
        // Recurring job every 200ms, starting at 100ms.
        s.schedule(new Job("every-200ms", () ->
                System.out.printf("[%3dms] recurring tick%n", System.currentTimeMillis() - t0), t0 + 100, 200));

        Thread.sleep(750); // let it run a while
        s.shutdown();
        System.out.println("scheduler stopped");
    }
}
