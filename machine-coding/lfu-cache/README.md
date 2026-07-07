# 🧊 LFU Cache

Fixed-capacity cache that evicts the **Least-Frequently-Used** key; ties broken by **Least-Recently-Used**. `get`/`put` in **O(1)**.

## Key idea
Track, per key, an access **frequency**, and group keys by frequency:
- `values`: key → value
- `counts`: key → frequency
- `freqList`: frequency → **ordered list of keys** at that frequency (front = oldest ⇒ evicted first on a tie)
- `minFreq`: the smallest frequency in use (the eviction bucket)

On access, a key moves from bucket `f` to `f+1`. If bucket `minFreq` empties, `minFreq++`. On overflow, drop the **front** of `freqList[minFreq]` (least frequent, and oldest among them).

C++ uses `list` + an iterator map (`keyIter`) for O(1) removal; Java uses `LinkedHashSet` which preserves recency order.

## LFU vs LRU
| | Evicts | Good when |
| --- | --- | --- |
| LRU | oldest-touched | recency matters (temporal locality) |
| LFU | least-often-used | popularity is stable over time |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
