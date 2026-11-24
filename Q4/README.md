# Q4: Thread Creation and the Thread Life Cycle

**Program:** `ScannerStations.java` · **Run:** `javac ScannerStations.java && java ScannerStations` · **Output:** `output/ScannerStations.txt` / `.png`
Each station scans 25 scripts, at 2 ms per script (`Thread.sleep`).

## Shared work
- **(a) `extends Thread`** (`StationThread`) and **(b) `implements Runnable`** (`StationTask` passed to `new Thread(task, name)`). Both print `total scanned = 100`.
- `start()` → state goes from `NEW` to `RUNNABLE`, `isAlive()` true. `sleep()` per script. `join()` on all four. After `join()`: `TERMINATED`, `isAlive()` false.
- Output orders over 10 runs: see Student B.

## Student A: thread design
| | `extends Thread` | `implements Runnable` |
|---|---|---|
| How | override `run()`; the object *is* the thread | separate task object; `new Thread(task)` runs it |
| Inheritance | uses up the single superclass | the class can still extend something else |
| Reuse | one object = one run (a Thread cannot be restarted) | the same task can be given to a Thread, an ExecutorService, etc. |
| Preferred when | quick demos, or when you really customise thread behaviour | **almost always**: it separates *what* to do from *how* it is run |

**Life-cycle states (observed with `getState()`, part g):**
```
 NEW ──start()──▶ RUNNABLE ◀──────────────────────────────┐
                    │  ├─ sleep(ms) / join(ms) ─▶ TIMED_WAITING ─┤
                    │  ├─ wait() / join()      ─▶ WAITING       ─┤ (notify / time-out / other thread ends)
                    │  └─ enters a locked synchronized block ─▶ BLOCKED ─┘ (lock released)
                    └─ run() returns ─▶ TERMINATED
```
Observed: `NEW` → `WAITING` (in `lock.wait()`) → `TIMED_WAITING` (in `sleep`) → `BLOCKED` (main holds the lock) → `TERMINATED`. Each scanner station passes through NEW → RUNNABLE ⇄ TIMED_WAITING (25 sleeps) → TERMINATED. Main is WAITING inside `join()` meanwhile.

**`run()` vs `start()`, with evidence (part f):**
```
inside run(): executing on thread 'main'        ← direct.run()
inside run(): executing on thread 'Station-X'   ← direct.start()
```
`run()` is an ordinary method call: it executes on the caller's stack, synchronously, and no new thread is created. `start()` asks the JVM to create a new OS thread with its own stack, which then calls `run()`. Calling `start()` twice throws `IllegalThreadStateException`.

**How `join()` guarantees the total:** `t.join()` makes main wait (WAITING) until `t` is TERMINATED. Looping `for (t : stations) { t.join(); total += t.scanned; }` means each count is read only after its station has finished. `join()` also creates a *happens-before* edge, so main is guaranteed to see the final value of `scanned`, not a stale cached one. Without `join()`, main would print a partial total (usually 0).

## Student B: behavioural experiments
**Finish order over 10 runs (same program, same input):** 8 *different* orders in 10 runs, e.g. `[S3, S1, S4, S2]`, `[S2, S3, S1, S4]`, `[S1, S2, S4, S3]`…
**Priorities** (S1 = 1, S4 = 10, CPU-bound work): S4's finishing position over 10 runs was `[2, 3, 4, 3, 2, 3, 4, 4, 4, 4]`. The **highest-priority thread never finished first** and often finished last. Priority had no reliable effect.
**Daemon station:** Station-4 needed 400 ms. Main ended after about 60 ms: the output shows `scanned 1`, `scanned 2`, then `main() ends now`, and **"FINISHED" never prints**. The JVM exits as soon as only daemon threads remain, killing them mid-batch.
**Explanation (thread scheduler):** Java threads map to OS threads, and the OS scheduler decides which runnable thread gets a core and for how long, based on timer interrupts, core count (2 here), other processes, and when each `sleep()` happens to wake up. Java priorities are only *hints*: on Linux the JVM ignores them by default, and Windows applies them loosely. So the order is not something the program controls.

## Joint conclusion
The same program produces different orders because **scheduling is nondeterministic**: it depends on timing outside the program (OS load, cores, wake-up jitter). A correct program must not depend on that order. Its *result* has to be the same for **every** possible interleaving (here the total of 100 is always correct, whatever order the stations finish in). Where order matters, it must be enforced explicitly with `join()`, locks, `wait/notify` or Futures, never assumed.

## Alternative concept: ExecutorService with a fixed pool
```java
ExecutorService pool = Executors.newFixedThreadPool(p);
List<Future<Integer>> fs = new ArrayList<>();
for (Script s : scripts) fs.add(pool.submit(() -> scan(s)));   // tasks, not threads
int total = 0; for (Future<Integer> f : fs) total += f.get();  // get() replaces join()
pool.shutdown();
```
**Measured** (2 ms per script, median of 3):
| scripts n | 4 Thread objects (n/4 each) | pool p=1 | p=2 | p=4 | p=8 | ideal n·2/p |
|---|---|---|---|---|---|---|
| 100 | 53 ms | 216 | 107 | 53 | **27** | 200/100/50/25 |
| 400 | 212 ms | 855 | 425 | 212 | **108** | 800/400/200/100 |

- **Time:** with p threads, each takes about n/p scripts, so completion ≈ ⌈n/p⌉ × t_script plus a small per-task overhead (the measurements track n·2/p within about 8%). Four hand-made threads equal a pool of 4. The pool lets you choose p independently of how the work is divided, and the work is split into many small tasks, which balances load automatically.
- **Memory:** each platform thread reserves a stack (default **≈ 1 MB** on 64-bit, `-Xss`) plus about 1–2 KB of JVM/OS structures. Hand-made threads cost one stack per station: 4 MB for 4, and n MB if you made one per script. A pool costs **p stacks, fixed**, however many tasks there are, plus O(n) small task objects in its queue (≈ 50–100 B each). Threads are also reused, which avoids paying creation cost (≈ 50–100 µs) per task.
