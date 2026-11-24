# Q6: Deadlock and Inter-Thread Communication

**Programs:** `TokenCounter.java` (Complete the Code), `MessAndWallets.java` (Part A wallets, Part B counter variants, alternatives)
**Run:** `javac *.java && java TokenCounter && java MessAndWallets` · **Outputs:** `output/`

## Complete the Code
```java
public synchronized void put(int token) throws InterruptedException {     // TODO 1
    while (count == buffer.length) wait();
    buffer[in] = token; in = (in + 1) % buffer.length; count++;
    notifyAll();
}
public synchronized int take() throws InterruptedException {               // TODO 2
    while (count == 0) wait();
    int token = buffer[out]; out = (out + 1) % buffer.length; count--;
    notifyAll();
    return token;
}
```
Output, 5 runs: `Sum of tokens collected: 210 (expected 210)` every time.

**`while` changed to `if`:** with the given setup (1 cook, 2 students, 20 tokens), 10 runs **still gave 210**. In that setup a woken student is usually the only one who can act. Under more contention (2 cooks, 3 students, 600 tokens, 5 runs) it **failed every time**: sums of 7,125–101,470 instead of 180,300, and the count went **as low as −488** (more takes than puts). Cause: with `if`, a thread that wakes up does not re-check the condition. Between `notifyAll()` and this thread re-acquiring the lock, another consumer may already have emptied the counter, so the woken thread takes from an empty buffer. Wake-ups can also be *spurious*, which the JVM spec permits.

## Part A: wallet transfers
### Student B: deadlock analysis and prevention
**Reliable deadlock:** T1 transfers A→B and T2 transfers B→A *at the same time*. Each locks its `from` wallet, sleeps 50 ms (so both certainly hold one lock), then requests the other.
Observed: **deadlocked in 10/10 trials**. The JVM's own detector (`ThreadMXBean.findDeadlockedThreads`) reports:
```
T1 (A->B) is BLOCKED waiting for Wallet@2d98a335, which is held by T2 (B->A)
T2 (B->A) is BLOCKED waiting for Wallet@b4c966a, which is held by T1 (A->B)
```
**The four (Coffman) conditions, all present:**
1. **Mutual exclusion:** a wallet's intrinsic lock can be held by only one thread.
2. **Hold and wait:** each thread holds its `from` lock while waiting for `to`.
3. **No preemption:** `synchronized` locks cannot be taken away; the thread must release them itself.
4. **Circular wait:** T1 waits for B's lock (held by T2), and T2 waits for A's lock (held by T1).

**Prevention: a consistent lock-ordering rule.** Always lock the wallet with the **lower id first**, whatever the direction of the transfer:
```java
Wallet first = from.id < to.id ? from : to, second = (first == from) ? to : from;
synchronized (first) { synchronized (second) { move money } }
```
Result: 8 threads × 50 opposite-direction transfers **finished**, total money 2,000 (conserved).
**Why it works:** it breaks condition 4. If every thread acquires locks in increasing id order, a cycle would need some thread holding a *higher* id while waiting for a *lower* one, which the rule never allows. With no cycle, the thread holding the highest lock in the system can always proceed, so there is always progress.

## Part B: mess token counter
### Student A: producer–consumer design
- **Why `wait()` goes inside `synchronized`:** `wait()` releases the object's monitor and parks the thread, so the thread must *own* the monitor, or it gets `IllegalMonitorStateException`. More importantly, the condition check (`count == 0`) and the decision to wait must be atomic with respect to `put()`. Otherwise a producer could add a token and `notify` *between* the consumer's check and its `wait()`, and that wake-up would be lost forever.
- **Why in a loop:** (1) another thread may consume the token between the `notifyAll()` and this thread re-acquiring the lock; (2) `notifyAll()` wakes producers *and* consumers, and not all of them can proceed; (3) spurious wake-ups are allowed. The loop re-checks the condition each time; the `if` experiment above shows the failure.
- **`notify()` instead of `notifyAll()`:** with **3 cooks, 3 students and capacity 1**, `while` + `notify()` **hung in 10/10 runs**, while `while` + `notifyAll()` hung in **0/10**. `notify()` wakes *one arbitrary* waiter. If a cook (producer) that just filled the counter wakes *another cook*, that cook re-checks, sees "full", and waits again. Now every thread is waiting, and nobody is left to notify the students. `notifyAll()` wakes everyone, so some thread that *can* proceed always gets to run. (With exactly 1 producer and 1 consumer, `notify()` would be safe, because the only waiter is always the right kind.)
- **Counter size over time** (first 60 put/take operations, given setup): `[1, 2, 3, 4, 5, 4, 3, 2, 1, 0, 1, 0, 1, 0, 1, 2, 3, 4, 5, 4, 3, 2, 3, 4, 5, …]`. The size range seen is **[0, 5]**: it never exceeded 5 and never dropped below 0. (With `if`, it went down to −488.)
- **`sleep()` vs `wait()`:**

| | `Thread.sleep(ms)` | `obj.wait()` |
|---|---|---|
| belongs to | `Thread` (static) | `Object` |
| locks | **keeps** every lock it holds | **releases** obj's monitor while waiting |
| needs synchronized? | no | yes, must own obj's monitor |
| wakes when | time elapses (or interrupt) | `notify`/`notifyAll` on obj, time-out, interrupt, spurious |
| use | pause / delay | wait for a *condition* changed by another thread |

## Joint conclusion
Both are about threads **waiting on each other**. Deadlock is a wait *cycle* (each holds something another needs), so nobody can ever proceed. The producer–consumer problem is coordinated waiting: consumers wait for "not empty", producers for "not full", and it fails in a similar way (everyone waiting, nobody notifying) if wake-ups go to the wrong thread. **Progress is ensured** in the wallet design by *lock ordering*, which makes a cycle impossible, and in the counter by *`wait` in a `while` loop plus `notifyAll()`*: every state change wakes every waiter, and each re-checks its own condition, so a thread that can proceed always will. Both rules are enforced by structure, not by timing.

## Alternative concept and complexity
```java
BlockingQueue<Integer> counter = new ArrayBlockingQueue<>(5);
counter.put(token);  int t = counter.take();         // blocking, bounded, no wait/notify code

boolean transfer(Wallet a, Wallet b, int amt) throws InterruptedException {
    while (true) {
        if (a.lock.tryLock(1, MILLISECONDS)) try {
            if (b.lock.tryLock(1, MILLISECONDS)) try { move; return true; } finally { b.lock.unlock(); }
        } finally { a.lock.unlock(); }
        Thread.sleep(random backoff);                   // release everything, retry
    }
}
```
| Design | Time per operation | Space | Measured |
|---|---|---|---|
| wait/notifyAll counter | O(1) per put/take, plus a context switch when blocking; `notifyAll` wakes all W waiters: O(W) | 5-slot array + 3 ints | ≈ 5,715 ns/token |
| ArrayBlockingQueue(5) | O(1); uses `ReentrantLock` + two conditions (`notFull`, `notEmpty`), so it signals only the right kind of waiter | 5-slot array + lock + 2 condition queues | ≈ 8,740 ns/token on this 2-core VM (blocking costs dominate both) |
| Lock-ordering transfer | O(1): 2 lock acquisitions, waiting at most for the current holder | 0 extra (intrinsic locks) | 400 transfers in 452 ms (includes the 1 ms simulated work) |
| tryLock + timeout | O(1) expected; O(r) for r retries under heavy contention (livelock possible without random back-off) | one `ReentrantLock` per wallet (~32 B) | 400 transfers in 5 ms, **1.00 attempts per transfer** |

**Less error-prone:** `ArrayBlockingQueue`. There is no hand-written `wait`/`notify`, no loop to forget and no notify/notifyAll choice. For transfers, **lock ordering** is simpler to get right and gives guaranteed progress. `tryLock` avoids deadlock even without a global ordering, but needs back-off to avoid livelock, and a failed attempt must leave no partial update.
