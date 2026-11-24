import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Q6 - Part A: wallet transfers (deadlock, lock ordering, tryLock).
 *      Part B: mess token counter (while vs if, notify vs notifyAll, size trace, ArrayBlockingQueue).
 */
public class MessAndWallets {

    // ===================================================== Part B: counter variants
    /** Configurable counter: useWhile=false -> 'if'; useNotifyAll=false -> notify(). Records size history. */
    static final class Counter {
        final int[] buf; int count, in, out;
        final boolean useWhile, useNotifyAll;
        int minSeen = Integer.MAX_VALUE, maxSeen = Integer.MIN_VALUE;
        final List<Integer> trace = new ArrayList<>();
        Counter(int cap, boolean useWhile, boolean useNotifyAll) { buf = new int[cap]; this.useWhile = useWhile; this.useNotifyAll = useNotifyAll; }
        synchronized void put(int t) throws InterruptedException {
            if (useWhile) { while (count == buf.length) wait(); } else { if (count == buf.length) wait(); }
            buf[in] = t; in = (in + 1) % buf.length; count++;
            record();
            if (useNotifyAll) notifyAll(); else notify();
        }
        synchronized int take() throws InterruptedException {
            if (useWhile) { while (count == 0) wait(); } else { if (count == 0) wait(); }
            int t = buf[out]; buf[out] = 0; out = (out + 1) % buf.length; count--;
            record();
            if (useNotifyAll) notifyAll(); else notify();
            return t;
        }
        void record() { minSeen = Math.min(minSeen, count); maxSeen = Math.max(maxSeen, count); if (trace.size() < 60) trace.add(count); }
    }

    /** producers x consumers; returns "sum=.. min=.. max=.." or "HUNG" if threads are stuck after 3 s. */
    static String runCounter(Counter c, int producers, int consumers, int tokensPerProducer) throws InterruptedException {
        AtomicInteger sum = new AtomicInteger();
        int total = producers * tokensPerProducer, perConsumer = total / consumers;
        List<Thread> all = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int base = p * tokensPerProducer;
            all.add(new Thread(() -> { try { for (int t = 1; t <= tokensPerProducer; t++) c.put(base + t); } catch (InterruptedException e) { } }));
        }
        for (int k = 0; k < consumers; k++)
            all.add(new Thread(() -> { try { for (int i = 0; i < perConsumer; i++) sum.addAndGet(c.take()); } catch (InterruptedException e) { } }));
        for (Thread t : all) { t.setDaemon(true); t.start(); }
        long deadline = System.currentTimeMillis() + 3000;
        for (Thread t : all) t.join(Math.max(1, deadline - System.currentTimeMillis()));
        for (Thread t : all) if (t.isAlive()) { for (Thread x : all) x.interrupt(); return "HUNG (all threads waiting, none can notify)"; }
        long expected = (long) total * (total + 1) / 2;
        return "sum=" + sum.get() + (sum.get() == expected ? " (correct)" : " (WRONG, expected " + expected + ")")
                + "  size range seen [" + c.minSeen + ", " + c.maxSeen + "]";
    }

    // ===================================================== Part A: wallets
    static final class Wallet {
        final int id; int balance; final ReentrantLock lock = new ReentrantLock();
        Wallet(int id, int b) { this.id = id; balance = b; }
    }

    /** Deadlock-prone: lock 'from' then 'to'. A->B and B->A at the same moment deadlock. */
    static void transferUnsafe(Wallet from, Wallet to, int amt) {
        synchronized (from) {
            pause(50);                                  // widen the window: both threads now hold one lock
            synchronized (to) { from.balance -= amt; to.balance += amt; }
        }
    }

    /** Lock ordering: always lock the lower id first, whatever the transfer direction. */
    static void transferOrdered(Wallet from, Wallet to, int amt) {
        Wallet first = from.id < to.id ? from : to, second = from.id < to.id ? to : from;
        synchronized (first) {
            pause(1);
            synchronized (second) { from.balance -= amt; to.balance += amt; }
        }
    }

    /** Alternative: tryLock with timeout - back off and retry instead of waiting forever. */
    static int transferTryLock(Wallet from, Wallet to, int amt) throws InterruptedException {
        int attempts = 0;
        while (true) {
            attempts++;
            if (from.lock.tryLock(1, TimeUnit.MILLISECONDS)) {
                try {
                    if (to.lock.tryLock(1, TimeUnit.MILLISECONDS)) {
                        try { from.balance -= amt; to.balance += amt; return attempts; }
                        finally { to.lock.unlock(); }
                    }
                } finally { from.lock.unlock(); }
            }
            Thread.sleep((long) (Math.random() * 3));   // random back-off breaks the symmetry
        }
    }

    static void pause(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Part A: wallet transfers ===");
        Wallet a = new Wallet(1, 1000), b = new Wallet(2, 1000);
        Thread t1 = new Thread(() -> transferUnsafe(a, b, 100), "T1 (A->B)");
        Thread t2 = new Thread(() -> transferUnsafe(b, a, 200), "T2 (B->A)");
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        t1.join(1000); t2.join(1000);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] dead = mx.findDeadlockedThreads();
        System.out.println("Unsafe A->B with B->A: after 1 s both alive=" + (t1.isAlive() && t2.isAlive())
                + ", JVM deadlock detector found " + (dead == null ? 0 : dead.length) + " deadlocked threads");
        if (dead != null) for (var info : mx.getThreadInfo(dead))
            System.out.println("   " + info.getThreadName() + " is BLOCKED waiting for " + info.getLockName()
                    + ", which is held by " + info.getLockOwnerName());

        int deadlocks = 0;
        for (int trial = 0; trial < 10; trial++) {
            Wallet x = new Wallet(1, 1000), y = new Wallet(2, 1000);
            Thread p = new Thread(() -> transferUnsafe(x, y, 1)), q = new Thread(() -> transferUnsafe(y, x, 1));
            p.setDaemon(true); q.setDaemon(true); p.start(); q.start(); p.join(500); q.join(500);
            if (p.isAlive() && q.isAlive()) deadlocks++;
        }
        System.out.println("Unsafe version: deadlocked in " + deadlocks + "/10 trials");

        Wallet c = new Wallet(1, 1000), d = new Wallet(2, 1000);
        long t0 = System.nanoTime();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final boolean dir = i % 2 == 0;
            Thread th = new Thread(() -> { for (int k = 0; k < 50; k++) { if (dir) transferOrdered(c, d, 3); else transferOrdered(d, c, 5); } });
            ts.add(th); th.start();
        }
        for (Thread th : ts) th.join(10_000);
        boolean ok = ts.stream().noneMatch(Thread::isAlive);
        System.out.printf("Lock ordering: 8 threads x 50 opposite-direction transfers finished=%s in %.0f ms, total money=%d (expected 2000)%n",
                ok, (System.nanoTime() - t0) / 1e6, c.balance + d.balance);

        Wallet e = new Wallet(1, 1000), f = new Wallet(2, 1000);
        AtomicInteger attempts = new AtomicInteger();
        ts.clear();
        t0 = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            final boolean dir = i % 2 == 0;
            Thread th = new Thread(() -> { try { for (int k = 0; k < 50; k++) attempts.addAndGet(dir ? transferTryLock(e, f, 3) : transferTryLock(f, e, 5)); } catch (InterruptedException ex) { } });
            ts.add(th); th.start();
        }
        for (Thread th : ts) th.join(10_000);
        System.out.printf("tryLock+timeout: finished=%s in %.0f ms, total money=%d, attempts per transfer=%.2f%n",
                ts.stream().noneMatch(Thread::isAlive), (System.nanoTime() - t0) / 1e6, e.balance + f.balance, attempts.get() / 400.0);

        System.out.println("\n=== Part B: mess token counter (capacity 5) ===");
        Counter good = new Counter(5, true, true);
        System.out.println("while + notifyAll, 1 cook, 2 students, 20 tokens : " + runCounter(good, 1, 2, 20));
        System.out.println("   size after each put/take (first 60): " + good.trace);
        for (int run = 1; run <= 10; run++)
            System.out.println("if    + notifyAll, 1 cook, 2 students (given setup), run " + run + " : " + runCounter(new Counter(5, false, true), 1, 2, 20));
        for (int run = 1; run <= 5; run++)
            System.out.println("if    + notifyAll, 2 cooks, 3 students, run " + run + "    : " + runCounter(new Counter(5, false, true), 2, 3, 300));
        int hung = 0;
        for (int run = 1; run <= 10; run++) if (runCounter(new Counter(1, true, false), 3, 3, 500).startsWith("HUNG")) hung++;
        System.out.println("while + notify (not All), 3 cooks, 3 students, capacity 1: hung in " + hung + "/10 runs");
        hung = 0;
        for (int run = 1; run <= 10; run++) if (runCounter(new Counter(1, true, true), 3, 3, 500).startsWith("HUNG")) hung++;
        System.out.println("while + notifyAll,         3 cooks, 3 students, capacity 1: hung in " + hung + "/10 runs");

        alternativeTiming();
    }

    /** Alternative: ArrayBlockingQueue(5) vs hand-written counter, 200,000 tokens, 1 producer / 2 consumers. */
    static void alternativeTiming() throws InterruptedException {
        System.out.println("\nput/take cost, 200,000 tokens, 1 producer + 2 consumers, capacity 5 (median of 5):");
        double[] own = new double[5], abq = new double[5];
        for (int r = -2; r < 5; r++) {
            Counter c = new Counter(5, true, true);
            long t0 = System.nanoTime(); runCounter(c, 1, 2, 200_000); long t1 = System.nanoTime();
            BlockingQueue<Integer> q = new ArrayBlockingQueue<>(5);
            Thread prod = new Thread(() -> { try { for (int i = 1; i <= 200_000; i++) q.put(i); } catch (InterruptedException e) { } });
            Thread[] cons = new Thread[2];
            for (int k = 0; k < 2; k++) cons[k] = new Thread(() -> { try { for (int i = 0; i < 100_000; i++) q.take(); } catch (InterruptedException e) { } });
            long t2 = System.nanoTime();
            prod.start(); for (Thread x : cons) x.start(); prod.join(); for (Thread x : cons) x.join();
            long t3 = System.nanoTime();
            if (r >= 0) { own[r] = (t1 - t0) / 200_000.0; abq[r] = (t3 - t2) / 200_000.0; }
        }
        java.util.Arrays.sort(own); java.util.Arrays.sort(abq);
        System.out.printf("   wait/notifyAll counter : %.0f ns per token%n   ArrayBlockingQueue(5)  : %.0f ns per token%n", own[2], abq[2]);
    }
}
