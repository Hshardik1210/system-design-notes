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
 *
 * Design patterns:
 *   - Producer/Consumer: main thread (producer) calls schedule() while the worker
 *     thread (consumer) pulls due jobs off the queue and runs them.
 *   - Priority Queue: a min-heap keyed by nextRun gives the next-due job in O(log n).
 */
public class Main {

    // A single unit of work to run: its name, the code to execute, when it should
    // next run, and how often to repeat (0 means run only once).
    static class Job {
        final String name;
        final Runnable action;         // the code this job executes when it fires
        long nextRunMillis;            // absolute time (epoch millis) when this job should next run
        final long intervalMillis;     // 0 => one-shot; > 0 => repeat every this many millis
        Job(String name, Runnable action, long nextRunMillis, long intervalMillis) {
            this.name = name; this.action = action; this.nextRunMillis = nextRunMillis; this.intervalMillis = intervalMillis;
        }
        // True if this job should be re-scheduled after it runs.
        boolean recurring() { return intervalMillis > 0; }
    }

    // The scheduler: holds pending jobs in a min-heap and runs a background worker
    // thread that sleeps until the earliest job is due, then executes it.
    static class Scheduler {
        // Min-heap ordered by nextRunMillis, so the soonest job is always on top.
        private final PriorityQueue<Job> pq = new PriorityQueue<>(Comparator.comparingLong(j -> j.nextRunMillis));
        private final ReentrantLock lock = new ReentrantLock();     // guards pq and running
        private final Condition cond = lock.newCondition();        // lets the worker sleep and be woken up
        private volatile boolean running = true;                   // flips to false on shutdown
        private Thread worker;

        // Launch the background worker thread that runs loop().
        void start() {
            worker = new Thread(this::loop, "scheduler");
            worker.start();
        }

        // Add a job to the heap. Signals the worker in case this new job is due
        // sooner than whatever it was currently waiting on.
        void schedule(Job job) {
            lock.lock();
            try { pq.offer(job); cond.signal(); } // wake worker: a new (maybe earlier) job exists
            finally { lock.unlock(); }
        }

        // The worker's main loop: repeatedly find the earliest job, sleep until it
        // is due, then run it. Holding the lock while waiting is intentional; await()
        // releases the lock while sleeping and re-acquires it on wake.
        private void loop() {
            lock.lock();
            try {
                while (running) {
                    if (pq.isEmpty()) { cond.await(); continue; } // nothing to do; wait for work
                    Job head = pq.peek();                          // earliest job, without removing it
                    long now = System.currentTimeMillis();
                    long delay = head.nextRunMillis - now;         // how long until it should run
                    if (delay > 0) {
                        cond.await(delay, java.util.concurrent.TimeUnit.MILLISECONDS); // sleep until due (or a new job arrives)
                        continue; // re-check head (it may have changed)
                    }
                    pq.poll(); // due -> take it
                    // Run outside the lock so long jobs don't block scheduling.
                    lock.unlock();
                    try { head.action.run(); } finally { lock.lock(); }
                    if (head.recurring() && running) {
                        // Recompute next run from "now" and put the job back in the heap.
                        head.nextRunMillis = System.currentTimeMillis() + head.intervalMillis;
                        pq.offer(head); // reschedule
                    }
                }
            } catch (InterruptedException ignored) {
            } finally { lock.unlock(); }
        }

        // Stop the scheduler: flip the flag, wake the worker so it notices, and
        // wait for the worker thread to finish.
        void shutdown() {
            lock.lock();
            try { running = false; cond.signalAll(); } finally { lock.unlock(); }
            try { worker.join(); } catch (InterruptedException ignored) {}
        }
    }

    // Demo: start the scheduler, submit one one-shot and one recurring job,
    // let them run for a while, then shut down cleanly.
    public static void main(String[] args) throws InterruptedException {
        Scheduler s = new Scheduler();
        s.start();
        long t0 = System.currentTimeMillis(); // reference time so output shows ms since start

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
