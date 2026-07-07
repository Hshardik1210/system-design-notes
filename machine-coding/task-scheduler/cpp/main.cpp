// TASK SCHEDULER / CRON (C++17)
//
// Min-heap (priority_queue) of jobs keyed by next-run time + a worker thread
// that waits on a condition_variable until the head job is due (or a new,
// earlier job arrives). Recurring jobs re-enqueue themselves after running.
//
// Key classes/types:
//   - Job:       one unit of work (name, action, nextRun time, repeat interval).
//   - JobCmp:    comparator that makes the priority_queue behave as a min-heap.
//   - Scheduler: owns the queue and the worker thread that runs due jobs.
//
// Design patterns:
//   - Producer/Consumer: main thread schedules jobs, worker thread consumes/runs them.
//   - Priority Queue: min-heap by nextRun gives the next-due job in O(log n).

#include <iostream>
#include <string>
#include <queue>
#include <vector>
#include <functional>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <chrono>
#include <memory>
using namespace std;

// steady_clock never jumps backward, so it is safe for measuring delays/timeouts.
using Clock = chrono::steady_clock;

// Helper for the demo: milliseconds elapsed since a reference time point t0.
static long long msSince(Clock::time_point t0) {
    return chrono::duration_cast<chrono::milliseconds>(Clock::now() - t0).count();
}

// One unit of work: what to run, when to run it next, and how often to repeat.
struct Job {
    string name;
    function<void()> action;   // the code this job executes when it fires
    Clock::time_point nextRun; // when this job should next run
    long long intervalMs;      // 0 => one-shot; > 0 => repeat every this many millis
    // True if this job should be re-scheduled after it runs.
    bool recurring() const { return intervalMs > 0; }
};

// Comparator for the priority_queue. priority_queue is a max-heap by default, so
// returning "a later than b" flips it into a min-heap: EARLIEST nextRun on top.
struct JobCmp {
    bool operator()(const shared_ptr<Job>& a, const shared_ptr<Job>& b) const {
        return a->nextRun > b->nextRun;
    }
};

// Owns the pending-job heap and a worker thread that sleeps until the earliest
// job is due, runs it, then reschedules it if it is recurring.
class Scheduler {
    priority_queue<shared_ptr<Job>, vector<shared_ptr<Job>>, JobCmp> pq; // min-heap by nextRun
    mutex mtx;                 // guards pq and running
    condition_variable cv;     // lets the worker sleep and be woken up
    bool running = true;       // set false on shutdown to end the loop
    thread worker;

    // Worker main loop: pick the earliest job, wait until it is due, run it.
    // wait/wait_until release the lock while sleeping and re-acquire it on wake.
    void loop() {
        unique_lock<mutex> lk(mtx);
        while (running) {
            if (pq.empty()) { cv.wait(lk); continue; } // nothing queued; wait for a job
            auto head = pq.top();                      // earliest job, without removing it
            auto now = Clock::now();
            if (head->nextRun > now) {
                cv.wait_until(lk, head->nextRun); // sleep until due or a new job arrives
                continue;                          // re-check the top (it may have changed)
            }
            pq.pop();
            lk.unlock();
            head->action();                       // run outside the lock
            lk.lock();
            if (head->recurring() && running) {
                // Recompute next run from "now" and push the job back into the heap.
                head->nextRun = Clock::now() + chrono::milliseconds(head->intervalMs);
                pq.push(head);
            }
        }
    }
public:
    // Launch the background worker thread that runs loop().
    void start() { worker = thread(&Scheduler::loop, this); }

    // Add a job to the heap, then notify the worker in case this job is due
    // sooner than whatever it was currently waiting on.
    void schedule(shared_ptr<Job> job) {
        { lock_guard<mutex> g(mtx); pq.push(move(job)); }
        cv.notify_all(); // wake worker: maybe an earlier job now exists
    }

    // Stop the scheduler: flip the flag, wake the worker, and join its thread.
    void shutdown() {
        { lock_guard<mutex> g(mtx); running = false; }
        cv.notify_all();
        if (worker.joinable()) worker.join();
    }
};

// Demo: start the scheduler, submit one one-shot and one recurring job, let them
// run for a while, then shut down cleanly.
int main() {
    Scheduler s;
    s.start();
    auto t0 = Clock::now(); // reference time so output shows ms since start

    s.schedule(make_shared<Job>(Job{"once-300ms",
        [t0] { printf("[%3lldms] one-shot fired\n", msSince(t0)); },
        t0 + chrono::milliseconds(300), 0}));

    s.schedule(make_shared<Job>(Job{"every-200ms",
        [t0] { printf("[%3lldms] recurring tick\n", msSince(t0)); },
        t0 + chrono::milliseconds(100), 200}));

    this_thread::sleep_for(chrono::milliseconds(750));
    s.shutdown();
    cout << "scheduler stopped\n";
    return 0;
}
