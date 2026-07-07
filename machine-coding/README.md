# 💻 Machine Coding Round — Implementations (Java + C++)

Runnable, interview-ready implementations of the classic **machine coding / low-level design (LLD)** problems. Each problem has:

- a **`java/`** folder — single-file `Main.java`, run with `javac Main.java && java Main`
- a **`cpp/`** folder — single-file `main.cpp`, run with `g++ -std=c++17 main.cpp -o main && ./main`
- a **`README.md`** — problem statement, design decisions, and the **design patterns** used

Every implementation is self-contained (no external libraries) and includes a `main()` demo you can run immediately.

---

## ⭐ Tier 1 — Must-Do staples

| Problem | Patterns |
| --- | --- |
| [Parking Lot](parking-lot/) | Strategy (pricing), Factory, Singleton |
| [Elevator System](elevator/) | State, Strategy (dispatch/scheduling) |
| [Vending Machine](vending-machine/) | **State**, Strategy (change-making) |
| [Splitwise](splitwise/) | Strategy (split), double-entry balances |
| [Snake & Ladder](snake-and-ladder/) | Unified Jump abstraction, turn queue |
| [Tic-Tac-Toe](tic-tac-toe/) | Extensible N×N board, O(1) win detection |
| [LRU Cache](lru-cache/) | HashMap + doubly linked list, O(1) |
| [LFU Cache](lfu-cache/) | Frequency buckets + recency, O(1) |
| [Rate Limiter](rate-limiter/) | Strategy (token bucket / sliding window) |
| [Logging Framework](logging-framework/) | Chain of Responsibility, Strategy (appenders) |
| [BookMyShow](bookmyshow/) | Two-phase seat locking, concurrency |
| [URL Shortener](url-shortener/) | base62 encode/decode, Singleton |

## 🔷 Tier 2 — Very common

| Problem | Patterns |
| --- | --- |
| [Notification System](notification-system/) | Observer, Strategy (channels), retry, templates |
| [In-Memory KV Store](kv-store/) | Nested transactions, undo logs |
| [Cab Booking](cab-booking/) | Strategy (pricing/surge), State (trip) |
| [Food Delivery](food-delivery/) | Order state machine, partner assignment |
| [Hotel Management](hotel-management/) | Date-range overlap, reservations |
| [ATM Machine](atm/) | **State**, greedy cash dispensing |
| [Meeting Room Scheduler](meeting-scheduler/) | Interval conflicts, sweep-line min rooms |
| [Coffee Vending Machine](coffee-machine/) | Recipe/ingredient inventory, thread-safe |
| [Library Management](library-management/) | Book vs copy modeling, loans, fines |
| [Stack Overflow Q&A](stack-overflow/) | Voting/reputation, tag index |

## 🔶 Tier 3 — Advanced / twist

| Problem | Patterns |
| --- | --- |
| [Task Scheduler / Cron](task-scheduler/) | Min-heap + worker, recurring jobs |
| [Text Editor Undo/Redo](text-editor/) | **Command** + Memento |
| [Card Game / Deck](card-game/) | Fisher-Yates shuffle, poker hand ranking |
| [Digital Wallet](digital-wallet/) | Double-entry ledger, ordered locking |
| [In-Memory File System](file-system/) | **Composite**, tree traversal |
| [Pub-Sub / Message Queue](pub-sub/) | Observer (push) + commit-log offsets (pull) |
| [Online Auction](online-auction/) | State, Observer (outbid), concurrency |
| [Airline / Flight Booking](airline-booking/) | Seat map by fare class, guarded booking |
| [CricBuzz Live Score](cricbuzz/) | Scoring rules, Observer |
| [Amazon Locker](amazon-locker/) | Best-fit slot allocation by size |

---

## 🗂️ Structure

```
machine-coding/
├── README.md              ← this index
└── <problem>/
    ├── README.md          ← statement + design + patterns
    ├── java/Main.java     ← javac Main.java && java Main
    └── cpp/main.cpp       ← g++ -std=c++17 main.cpp -o main && ./main
```

## ▶️ How to run

```bash
# Java
cd machine-coding/parking-lot/java && javac Main.java && java Main

# C++
cd machine-coding/parking-lot/cpp && g++ -std=c++17 main.cpp -o main && ./main
```
