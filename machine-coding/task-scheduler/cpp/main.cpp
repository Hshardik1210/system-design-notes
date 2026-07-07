// TASK SCHEDULER / CRON (C++17)
//
// Min-heap (priority_queue) of jobs keyed by next-run time + a worker thread
// that waits on a condition_variable until the head job is due (or a new,
// earlier job arrives). Recurring jobs re-enqueue themselves after running.

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

using Clock = chrono::steady_clock;

static long long msSince(Clock::time_point t0) {
    return chrono::duration_cast<chrono::milliseconds>(Clock::now() - t0).count();
}

struct Job {
    string name;
    function<void()> action;
    Clock::time_point nextRun;
    long long intervalMs; // 0 => one-shot
    bool recurring() const { return intervalMs > 0; }
};

// Order so the EARLIEST nextRun is on top (min-heap).
struct JobCmp {
    bool operator()(const shared_ptr<Job>& a, const shared_ptr<Job>& b) const {
        return a->nextRun > b->nextRun;
    }
};

class Scheduler {
    priority_queue<shared_ptr<Job>, vector<shared_ptr<Job>>, JobCmp> pq;
    mutex mtx;
    condition_variable cv;
    bool running = true;
    thread worker;

    void loop() {
        unique_lock<mutex> lk(mtx);
        while (running) {
            if (pq.empty()) { cv.wait(lk); continue; }
            auto head = pq.top();
            auto now = Clock::now();
            if (head->nextRun > now) {
                cv.wait_until(lk, head->nextRun); // sleep until due or a new job arrives
                continue;
            }
            pq.pop();
            lk.unlock();
            head->action();                       // run outside the lock
            lk.lock();
            if (head->recurring() && running) {
                head->nextRun = Clock::now() + chrono::milliseconds(head->intervalMs);
                pq.push(head);
            }
        }
    }
public:
    void start() { worker = thread(&Scheduler::loop, this); }

    void schedule(shared_ptr<Job> job) {
        { lock_guard<mutex> g(mtx); pq.push(move(job)); }
        cv.notify_all(); // wake worker: maybe an earlier job now exists
    }

    void shutdown() {
        { lock_guard<mutex> g(mtx); running = false; }
        cv.notify_all();
        if (worker.joinable()) worker.join();
    }
};

int main() {
    Scheduler s;
    s.start();
    auto t0 = Clock::now();

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
