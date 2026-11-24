# Q5: Race Conditions in a Cinema Seat-Booking System

**Programs:** `unsafe/SeatBooking.java` (TODO 1 written without `synchronized`), `SeatBooking.java` (completed), `BookingLab.java` (reliable reproduction, strategy comparison)
**Run:** `javac SeatBooking.java BookingLab.java && java SeatBooking && java BookingLab` · `cd unsafe && javac SeatBooking.java && java SeatBooking`
**Outputs:** `output/SeatBooking_unsynchronized.txt`, `output/SeatBooking.txt`, `output/BookingLab.txt` (+ `.png`)

## Complete the Code
```java
synchronized boolean book(int seat) {      // TODO 1 (completed)
    if (booked[seat]) return false;        // check ...
    booked[seat] = true;                   // ... then act, now atomic
    bookingsMade++;
    return true;
}
```
| Version | 10 runs |
|---|---|
| without `synchronized` | `Seats sold: 10` in all 10 runs |
| with `synchronized` | `Seats sold: 10` in all 10 runs (matches the expected output) |

**Honest observation:** the given unsynchronized program happened to sell exactly 10 every time on this machine. The 100 threads are created and started one after another, and `book()` takes a few nanoseconds, so each thread usually finishes before the next one starts. The race is **still there; it is just rarely hit**. That is what makes races dangerous: they pass testing and fail in production under load.

**Why it can sell more than 10:** `if (booked[seat])` and `booked[seat] = true` are two separate steps. Two threads can both read `false` before either writes `true`, and both then "sell" the seat. `bookingsMade++` is also three steps (read, add, write), so increments can be lost. That is why the count can be wrong in *either* direction.

## Student A: producing and explaining the race
**Reliable test (`BookingLab` part A):** all 100 threads wait on a `CountDownLatch` and are released *together*, and a 1 ms pause between the check and the act stands in for the payment or database call that real booking code makes there.

| Unsafe, 20 trials (10 seats) | Seats sold per trial |
|---|---|
| observed | `100 46 63 46 45 35 72 72 80 28 57 60 57 64 92 72 67 63 60 59` |
| double booking | **20 / 20 trials**, worst **100 sold for 10 seats** |
| synchronized method / block | wrong count in **0 / 20** trials each |

**Interleaving of two threads for seat 3:**
| step | Thread A | Thread B | booked[3] | sold |
|---|---|---|---|---|
| 1 | reads booked[3] → false | | false | 0 |
| 2 | | reads booked[3] → false | false | 0 |
| 3 | (payment…) writes booked[3] = true | | true | 0 |
| 4 | | writes booked[3] = true | true | 0 |
| 5 | reads sold = 0 | reads sold = 0 | | 0 |
| 6 | writes sold = 1 | writes sold = 1 | | **1** |
Both customers get seat 3 (**double booking**) and one increment is **lost**.

**Exact critical section:** from reading `booked[seat]` to writing `booked[seat] = true`, **including `bookingsMade++`**. These must run as one indivisible step per seat. Validation, logging and rendering the seat map are *not* part of it.

## Student B: comparing synchronization strategies
**10,000 bookings (8 threads × 1,250) on 500 seats, median of 7 runs:**
| Strategy | ns / booking | sold (must be 500) |
|---|---|---|
| unsynchronized (with the 1 ms window) | ≈ 8,269 (the sleep dominates) | **580: WRONG** |
| synchronized **method** | ≈ 149 | 500 |
| synchronized **block** (critical section only) | ≈ 127 | 500 |
| AtomicBoolean[] CAS | ≈ 115 | 500 |
| ReentrantLock | ≈ 196 | 500 |
| ConcurrentHashMap.putIfAbsent | ≈ 257 | 500 |

- **Whole method vs critical section:** the block version is about **15% faster** because the non-critical `validate()` work runs *outside* the lock, so threads overlap on it and hold the lock for less time. The more non-critical work there is (payment, logging), the bigger the gap.
- **Intrinsic lock used:** a `synchronized` instance method locks **`this`** (the `SeatBooking` / screen object). The block version locks the private field **`lock`** (`synchronized (lock)`), a private lock object that outside code cannot also lock and so cannot interfere with. Every Java object has one intrinsic monitor; only one thread at a time can hold it.
- **Static synchronization:** a `static synchronized` method locks the **Class object** (`SeatBooking.class`), one lock for *all* screens. It would be needed only for state shared across all instances, such as a **cinema-wide ticket-number counter** or a total-tickets-sold figure. Using it for `book()` would serialise every screen against every other screen for no reason.

## Joint engineering decision: 20 screens
Deploy **one lock per screen** (an instance lock or a private lock object per `Screen`, with a synchronized *block* around only the critical section), or **lock-free per seat** (`AtomicBoolean[]` with `compareAndSet`). Bookings on different screens never compete, so 20 screens give up to 20× parallelism. Inside a screen, a per-seat CAS means two customers contend only when they want the *same* seat. Avoid a single global/static lock: it would make all 20 screens queue behind each other. A cross-screen counter, if needed, should be an `AtomicLong`, not a global lock.

## Alternative concept and complexity (s seats)
```java
AtomicBoolean[] booked;  boolean book(int s) { return booked[s].compareAndSet(false, true); }   // lock-free
ReentrantLock lock;      lock.lock(); try { check; act; } finally { lock.unlock(); }          // explicit lock
ConcurrentHashMap<Integer,Long> owner;  boolean book(int s) { return owner.putIfAbsent(s, customer) == null; }
```
| Approach | Time per booking, uncontended | Under contention | Extra space for s seats |
|---|---|---|---|
| synchronized | O(1), ≈ 127–149 ns | threads **block** (park) and queue on one lock | 0 (monitor is in the object header) + `boolean[s]` = s bytes |
| AtomicBoolean[] CAS | O(1), **≈ 115 ns** (fastest) | a failed CAS returns immediately (no blocking, no retry needed: someone else got the seat) | s objects × ≈ 16 B + s references ≈ **20 s bytes** |
| ReentrantLock | O(1), ≈ 196 ns | blocks like synchronized; adds `tryLock(timeout)`, fairness, interruptibility | one lock object (~32 B) + queue nodes per waiting thread |
| ConcurrentHashMap.putIfAbsent | O(1) average, ≈ 257 ns | per-bin locking, so parallel across seats | one node per **sold** seat (≈ 48–64 B) + table; it also records *who* booked |
