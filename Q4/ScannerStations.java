import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Q4 - Four scanner stations scanning answer scripts in parallel.
 *  (a) extends Thread   (b) implements Runnable   - start, sleep, join, isAlive
 *  (c) 10 runs: finish order        (d) priorities     (e) daemon station
 *  (f) run() vs start() evidence    (g) life-cycle states observed with getState()
 *  (h) ExecutorService alternative with timing
 */
public class ScannerStations {

    static final List<String> finishOrder = new CopyOnWriteArrayList<>();
    static final int SCRIPTS_PER_STATION = 25, MS_PER_SCRIPT = 2;

    // (a) extending Thread
    static class StationThread extends Thread {
        int scanned;
        StationThread(String name) { super(name); }
        @Override public void run() {
            for (int i = 0; i < SCRIPTS_PER_STATION; i++) {
                try { Thread.sleep(MS_PER_SCRIPT); } catch (InterruptedException e) { return; }   // TIMED_WAITING
                scanned++;
            }
            finishOrder.add(getName());
        }
    }

    // (b) implementing Runnable - the task is separate from the thread that runs it
    static class StationTask implements Runnable {
        final String name; int scanned;
        StationTask(String n) { name = n; }
        @Override public void run() {
            for (int i = 0; i < SCRIPTS_PER_STATION; i++) {
                try { Thread.sleep(MS_PER_SCRIPT); } catch (InterruptedException e) { return; }
                scanned++;
            }
            finishOrder.add(name);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("(a) extends Thread:");
        StationThread[] st = new StationThread[4];
        for (int i = 0; i < 4; i++) st[i] = new StationThread("Station-" + (i + 1));
        System.out.println("    before start: " + st[0].getName() + " state=" + st[0].getState() + " isAlive=" + st[0].isAlive());
        for (StationThread t : st) t.start();
        System.out.println("    after start : " + st[0].getName() + " state=" + st[0].getState() + " isAlive=" + st[0].isAlive());
        int total = 0;
        for (StationThread t : st) { t.join(); total += t.scanned; }      // main WAITS for each station
        System.out.println("    after join  : " + st[0].getName() + " state=" + st[0].getState() + " isAlive=" + st[0].isAlive());
        System.out.println("    total scanned = " + total + " (expected " + 4 * SCRIPTS_PER_STATION + ")");

        System.out.println("\n(b) implements Runnable:");
        StationTask[] tasks = new StationTask[4];
        Thread[] th = new Thread[4];
        for (int i = 0; i < 4; i++) { tasks[i] = new StationTask("Station-" + (i + 1)); th[i] = new Thread(tasks[i], tasks[i].name); th[i].start(); }
        for (Thread t : th) t.join();
        int total2 = 0; for (StationTask t : tasks) total2 += t.scanned;
        System.out.println("    total scanned = " + total2);

        System.out.println("\n(c) Finish order over 10 runs (same program, same input):");
        Map<String, Integer> distinct = new LinkedHashMap<>();
        for (int run = 1; run <= 10; run++) {
            finishOrder.clear();
            Thread[] t = new Thread[4];
            for (int i = 0; i < 4; i++) { t[i] = new Thread(new StationTask("S" + (i + 1))); }
            for (Thread x : t) x.start();
            for (Thread x : t) x.join();
            System.out.println("    run " + String.format("%2d", run) + ": " + finishOrder);
            distinct.merge(finishOrder.toString(), 1, Integer::sum);
        }
        System.out.println("    distinct orders seen: " + distinct.size());

        System.out.println("\n(d) Priorities: S1=MIN(1) ... S4=MAX(10), CPU-bound work, 10 runs - position of S4 when it finished:");
        List<Integer> s4pos = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            finishOrder.clear();
            Thread[] t = new Thread[4];
            int[] prio = {Thread.MIN_PRIORITY, 4, 7, Thread.MAX_PRIORITY};
            for (int i = 0; i < 4; i++) {
                final String n = "S" + (i + 1);
                t[i] = new Thread(() -> { long x = 0; for (int k = 0; k < 30_000_000; k++) x += k % 7; if (x < 0) System.out.println(); finishOrder.add(n); });
                t[i].setPriority(prio[i]);
            }
            for (Thread x : t) x.start();
            for (Thread x : t) x.join();
            s4pos.add(finishOrder.indexOf("S4") + 1);
        }
        System.out.println("    S4 (MAX priority) finished in position: " + s4pos + "   (1 = first)");
        System.out.println("    available processors: " + Runtime.getRuntime().availableProcessors());

        System.out.println("\n(f) run() vs start():");
        Thread direct = new Thread(() -> System.out.println("    inside run(): executing on thread '" + Thread.currentThread().getName() + "'"), "Station-X");
        direct.run();     // plain method call on main's stack
        direct.start();   // new call stack on Station-X
        direct.join();

        System.out.println("\n(g) Life-cycle states observed for one station:");
        Object lock = new Object();
        Thread s = new Thread(() -> {
            synchronized (lock) { try { lock.wait(); } catch (InterruptedException ignored) { } }   // WAITING
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }                       // TIMED_WAITING
            synchronized (lock) { }                                                                  // BLOCKED if main holds lock
        });
        System.out.println("    " + s.getState() + "  (created, not started)");
        s.start(); Thread.sleep(20);
        System.out.println("    " + s.getState() + "  (in lock.wait())");
        synchronized (lock) { lock.notify(); }
        Thread.sleep(20);
        System.out.println("    " + s.getState() + "  (in Thread.sleep)");
        synchronized (lock) { Thread.sleep(80); System.out.println("    " + s.getState() + "  (wants the lock main is holding)"); }
        s.join();
        System.out.println("    " + s.getState() + "  (run() returned)");

        executorAlternative();
        daemonDemo();
    }

    static void executorAlternative() throws Exception {
        System.out.println("\n(h) n scripts, 2 ms each: one Thread per station vs fixed pool ExecutorService (median of 3)");
        System.out.printf("    %8s %4s %20s %20s %16s%n", "scripts", "p", "4 Thread objects ms", "pool(p) tasks ms", "ideal n*2/p ms");
        for (int n : new int[]{100, 400}) {
            for (int p : new int[]{1, 2, 4, 8}) {
                long[] a = new long[3], b = new long[3];
                for (int r = 0; r < 3; r++) {
                    long t0 = System.nanoTime();
                    Thread[] t = new Thread[4];
                    for (int i = 0; i < 4; i++) { t[i] = new Thread(() -> { for (int k = 0; k < n / 4; k++) sleep(MS_PER_SCRIPT); }); t[i].start(); }
                    for (Thread x : t) x.join();
                    long t1 = System.nanoTime();
                    ExecutorService pool = Executors.newFixedThreadPool(p);
                    List<Future<Integer>> fs = new ArrayList<>();
                    for (int k = 0; k < n; k++) fs.add(pool.submit(() -> { sleep(MS_PER_SCRIPT); return 1; }));
                    int sum = 0; for (Future<Integer> f : fs) sum += f.get();
                    pool.shutdown();
                    long t2 = System.nanoTime();
                    a[r] = (t1 - t0) / 1_000_000; b[r] = (t2 - t1) / 1_000_000;
                    if (sum != n) throw new AssertionError();
                }
                java.util.Arrays.sort(a); java.util.Arrays.sort(b);
                System.out.printf("    %8d %4d %20d %20d %16d%n", n, p, a[1], b[1], (long) n * MS_PER_SCRIPT / p);
            }
        }
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** (e) Station-4 made a daemon: main ends after joining only the others; the JVM kills the daemon mid-batch. */
    static void daemonDemo() {
        System.out.println("\n(e) Daemon station (needs 400 ms, main ends after ~60 ms):");
        Thread d = new Thread(() -> {
            for (int i = 1; i <= 20; i++) { sleep(20); System.out.println("    daemon Station-4 scanned " + i); }
            System.out.println("    daemon Station-4 FINISHED");   // never printed
        });
        d.setDaemon(true);
        d.start();
        sleep(60);
        System.out.println("    main() ends now - JVM exits because only daemon threads remain");
    }
}
