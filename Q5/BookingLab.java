import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Q5 - Reproducing the race reliably, then comparing synchronization strategies.
 * Every strategy books seats through the same interface; "sold" counts successful book() calls.
 */
public class BookingLab {

    interface Screen { boolean book(int seat); int sold(); }

    /** No synchronization. A start latch lines all threads up so they hit the check together. */
    static final class Unsafe implements Screen {
        final boolean[] booked; int sold;
        Unsafe(int s) { booked = new boolean[s]; }
        public boolean book(int seat) {
            if (booked[seat]) return false;            // T1 and T2 can both see 'false' here ...
            try { Thread.sleep(1); } catch (InterruptedException e) { }   // payment/DB call between check and act
            booked[seat] = true;                       // ... and both mark it booked
            sold++;                                    // lost-update race on the counter as well
            return true;
        }
        public int sold() { return sold; }
    }

    /** Whole method synchronized: lock held for the entire call. */
    static final class SyncMethod implements Screen {
        final boolean[] booked; int sold;
        SyncMethod(int s) { booked = new boolean[s]; }
        public synchronized boolean book(int seat) {
            validate(seat);                            // non-critical work also runs under the lock
            if (booked[seat]) return false;
            booked[seat] = true; sold++; return true;
        }
        public synchronized int sold() { return sold; }
    }

    /** Only the critical section locked. */
    static final class SyncBlock implements Screen {
        final boolean[] booked; int sold; final Object lock = new Object();
        SyncBlock(int s) { booked = new boolean[s]; }
        public boolean book(int seat) {
            validate(seat);                            // outside the lock
            synchronized (lock) {                      // critical section: check + mark + count
                if (booked[seat]) return false;
                booked[seat] = true; sold++; return true;
            }
        }
        public int sold() { synchronized (lock) { return sold; } }
    }

    /** Alternative 1: one AtomicBoolean per seat, compareAndSet - lock-free, per-seat. */
    static final class Atomic implements Screen {
        final AtomicBoolean[] booked; final AtomicInteger sold = new AtomicInteger();
        Atomic(int s) { booked = new AtomicBoolean[s]; for (int i = 0; i < s; i++) booked[i] = new AtomicBoolean(); }
        public boolean book(int seat) {
            validate(seat);
            if (!booked[seat].compareAndSet(false, true)) return false;
            sold.incrementAndGet(); return true;
        }
        public int sold() { return sold.get(); }
    }

    /** Alternative 2: ReentrantLock (explicit lock; could use tryLock/fairness). */
    static final class Locked implements Screen {
        final boolean[] booked; int sold; final ReentrantLock lock = new ReentrantLock();
        Locked(int s) { booked = new boolean[s]; }
        public boolean book(int seat) {
            validate(seat);
            lock.lock();
            try { if (booked[seat]) return false; booked[seat] = true; sold++; return true; }
            finally { lock.unlock(); }
        }
        public int sold() { lock.lock(); try { return sold; } finally { lock.unlock(); } }
    }

    /** Alternative 3: ConcurrentHashMap seat -> customer, putIfAbsent is atomic. */
    static final class MapBased implements Screen {
        final ConcurrentHashMap<Integer, Long> owner = new ConcurrentHashMap<>();
        public boolean book(int seat) {
            validate(seat);
            return owner.putIfAbsent(seat, Thread.currentThread().getId()) == null;
        }
        public int sold() { return owner.size(); }
    }

    /** Simulated non-critical work (input validation / seat-map rendering), ~0.3 us. */
    static void validate(int seat) {
        long x = seat;
        for (int i = 0; i < 60; i++) x = x * 31 + i;
        if (x == 42) System.out.print("");
    }

    interface Factory { Screen make(int seats); }

    /** threads x perThread bookings; every seat is wanted by 'competitors' users. Returns sold. */
    static int race(Screen s, int seats, int threads, int perThread) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        Thread[] t = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int base = i;
            t[i] = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int k = 0; k < perThread; k++) s.book((base + k * 7) % seats);
            });
            t[i].start();
        }
        go.countDown();
        for (Thread x : t) x.join();
        return s.sold();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A) Reliable reproduction: 10 seats, 100 threads released together by a latch, 20 trials");
        int over = 0, worst = 0;
        StringBuilder sb = new StringBuilder();
        for (int trial = 0; trial < 20; trial++) {
            int sold = race(new Unsafe(10), 10, 100, 1);
            sb.append(sold).append(' ');
            if (sold > 10) over++;
            worst = Math.max(worst, sold);
        }
        System.out.println("   unsafe seats sold per trial: " + sb.toString().trim());
        System.out.println("   trials with double booking: " + over + "/20, worst = " + worst + " seats sold for 10 seats");
        for (String name : new String[]{"SyncMethod", "SyncBlock"}) {
            int bad = 0;
            for (int trial = 0; trial < 20; trial++) {
                Screen s = name.equals("SyncMethod") ? new SyncMethod(10) : new SyncBlock(10);
                if (race(s, 10, 100, 1) != 10) bad++;
            }
            System.out.println("   " + name + ": trials with a wrong count = " + bad + "/20");
        }

        System.out.println("\nB) Time for 10,000 bookings (8 threads x 1,250) on 500 seats; median of 7 runs");
        System.out.printf("   %-26s %12s %14s %10s%n", "strategy", "time (ms)", "ns/booking", "sold");
        Object[][] strategies = {
            {"unsynchronized (WRONG)", (Factory) Unsafe::new},
            {"synchronized method", (Factory) SyncMethod::new},
            {"synchronized block", (Factory) SyncBlock::new},
            {"AtomicBoolean[] CAS", (Factory) Atomic::new},
            {"ReentrantLock", (Factory) Locked::new},
            {"ConcurrentHashMap.putIfAbsent", (Factory) s -> new MapBased()},
        };
        for (Object[] st : strategies) {
            Factory f = (Factory) st[1];
            long[] t = new long[7]; int sold = 0;
            for (int r = -3; r < 7; r++) {
                Screen s = f.make(500);
                long t0 = System.nanoTime();
                sold = race(s, 500, 8, 1250);
                if (r >= 0) t[r] = System.nanoTime() - t0;
            }
            java.util.Arrays.sort(t);
            System.out.printf("   %-26s %12.2f %14.1f %10d%n", st[0], t[3] / 1e6, t[3] / 10_000.0, sold);
        }
        System.out.println("   (500 seats: every seat is wanted 20 times, so exactly 500 must be sold)");
    }
}
