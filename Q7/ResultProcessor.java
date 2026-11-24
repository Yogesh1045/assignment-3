import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q7 - Semester result processing: 10,000 score files, some corrupt or missing.
 * One Callable per file -> Future<Outcome>. A failure becomes a FAILED outcome; it never stops the run.
 * Shared results: ConcurrentHashMap<regNo, Result>. Summary built only after every Future is resolved.
 *
 * File format (one per student, data/<regNo>.txt):
 *   line 1: regNo
 *   line 2: five marks separated by commas, each 0..100
 */
public class ResultProcessor {

    enum Status { PASS, FAIL, MISSING_FILE, CORRUPT_FILE, INVALID_MARKS, TIMEOUT, UNEXPECTED }

    record Outcome(String regNo, Status status, double average, String detail) { }

    static int SIMULATED_IO_MICROS = 400;      // network-share read latency per file

    /** The unit of parallel work: read + validate + compute ONE student's result. */
    static final class ScoreTask implements Callable<Outcome> {
        final Path file; final String regNo;
        ScoreTask(Path f, String reg) { file = f; regNo = reg; }
        @Override public Outcome call() {
            try {
                if (SIMULATED_IO_MICROS > 0) busyWaitMicros(SIMULATED_IO_MICROS);  // I/O latency (thread blocked, CPU idle in real life)
                List<String> lines = Files.readAllLines(file);
                if (lines.size() < 2 || !lines.get(0).trim().equals(regNo))
                    return new Outcome(regNo, Status.CORRUPT_FILE, 0, "header/regNo mismatch");
                String[] parts = lines.get(1).split(",");
                if (parts.length != 5) return new Outcome(regNo, Status.CORRUPT_FILE, 0, "expected 5 marks, found " + parts.length);
                int sum = 0, min = 100;
                for (String p : parts) {
                    int m = Integer.parseInt(p.trim());                         // NumberFormatException -> CORRUPT
                    if (m < 0 || m > 100) return new Outcome(regNo, Status.INVALID_MARKS, 0, "mark out of range: " + m);
                    sum += m; min = Math.min(min, m);
                }
                double avg = sum / 5.0;
                return new Outcome(regNo, avg >= 50 && min >= 40 ? Status.PASS : Status.FAIL, avg, "");
            } catch (NoSuchFileException e) {
                return new Outcome(regNo, Status.MISSING_FILE, 0, e.getMessage());
            } catch (NumberFormatException e) {
                return new Outcome(regNo, Status.CORRUPT_FILE, 0, "non-numeric mark: " + e.getMessage());
            } catch (IOException e) {
                return new Outcome(regNo, Status.CORRUPT_FILE, 0, e.toString());
            }
        }
    }

    /** Simulated blocking I/O that does not depend on sleep granularity. */
    static void busyWaitMicros(int us) {
        try { TimeUnit.MICROSECONDS.sleep(us); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Processes all files with a pool of p threads. Guarantees one outcome per input file. */
    static Map<String, Outcome> process(List<String> regNos, Path dir, int p) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(p);
        Map<String, Outcome> results = new ConcurrentHashMap<>();
        List<Future<Outcome>> futures = new ArrayList<>(regNos.size());
        try {
            for (String r : regNos) futures.add(pool.submit(new ScoreTask(dir.resolve(r + ".txt"), r)));
            for (int i = 0; i < futures.size(); i++) {
                String reg = regNos.get(i);
                Outcome o;
                try {
                    o = futures.get(i).get(5, TimeUnit.SECONDS);                // one slow file cannot hang the run
                } catch (ExecutionException e) {                                // a bug inside call() - contained
                    o = new Outcome(reg, Status.UNEXPECTED, 0, e.getCause().toString());
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    o = new Outcome(reg, Status.TIMEOUT, 0, "no result in 5 s");
                }
                results.put(reg, o);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        return results;
    }

    static Map<Status, Integer> summary(Map<String, Outcome> results) {
        Map<Status, Integer> s = new java.util.EnumMap<>(Status.class);
        for (Status st : Status.values()) s.put(st, 0);
        for (Outcome o : results.values()) s.merge(o.status(), 1, Integer::sum);
        return s;
    }

    // ------------------------------------------------------------ test data
    /** Writes n files; every 97th is deleted (missing), every 89th corrupt, every 211th has an out-of-range mark. */
    static List<String> generate(Path dir, int n) throws IOException {
        Files.createDirectories(dir);
        List<String> regs = new ArrayList<>(n);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            String reg = "1923" + String.format("%06d", i);
            regs.add(reg);
            Path f = dir.resolve(reg + ".txt");
            if (i % 97 == 96) { Files.deleteIfExists(f); continue; }                   // missing
            StringBuilder marks = new StringBuilder();
            for (int k = 0; k < 5; k++) marks.append(k == 0 ? "" : ",").append(30 + r.nextInt(71));
            String body = reg + "\n" + marks;
            if (i % 89 == 88) body = reg + "\n" + "78,8O,91,66";                       // corrupt: letter O, 4 marks
            else if (i % 211 == 210) body = reg + "\n" + "78,80,191,66,70";            // mark 191
            Files.writeString(f, body);
        }
        return regs;
    }

    public static void main(String[] args) throws Exception {
        // ---------- hand-checked set ----------
        Path small = Path.of("data/small");
        Files.createDirectories(small);
        String[][] hand = {
            {"S01", "S01\n80,75,90,60,70"},         // avg 75, min 60 -> PASS
            {"S02", "S02\n45,50,55,60,40"},         // avg 50, min 40 -> PASS (boundary)
            {"S03", "S03\n90,90,90,90,39"},         // min 39 -> FAIL
            {"S04", "S04\n50,50,50,50,49"},         // avg 49.8 -> FAIL
            {"S05", null},                          // MISSING_FILE
            {"S06", "S06\n70,7O,80,90,60"},         // letter O -> CORRUPT_FILE
            {"S07", "S07\n70,80,90"},               // 3 marks -> CORRUPT_FILE
            {"S08", "S99\n70,80,90,60,50"},         // wrong regNo -> CORRUPT_FILE
            {"S09", "S09\n70,80,101,60,50"},        // 101 -> INVALID_MARKS
            {"S10", "S10\n100,100,100,100,100"},    // PASS
        };
        List<String> regs = new ArrayList<>();
        for (String[] h : hand) {
            regs.add(h[0]);
            Path f = small.resolve(h[0] + ".txt");
            if (h[1] == null) Files.deleteIfExists(f); else Files.writeString(f, h[1]);
        }
        Map<String, Outcome> res = process(regs, small, 4);
        System.out.println("Hand-checked set (10 files):");
        for (String r : regs) System.out.printf("   %-4s %-14s %s%n", r, res.get(r).status(), res.get(r).detail());
        Map<Status, Integer> s = summary(res);
        System.out.println("   summary: " + s);
        boolean ok = s.get(Status.PASS) == 3 && s.get(Status.FAIL) == 2 && s.get(Status.MISSING_FILE) == 1
                && s.get(Status.CORRUPT_FILE) == 3 && s.get(Status.INVALID_MARKS) == 1;
        System.out.println("   expected by hand: PASS 3, FAIL 2, MISSING 1, CORRUPT 3, INVALID 1 -> " + (ok ? "MATCH" : "MISMATCH"));

        // ---------- 10,000 files, pools of 1, 2, 4, 8 (+16, 32) ----------
        int n = 10_000;
        List<String> all = generate(Path.of("data/big"), n);
        long expMissing = 0, expCorrupt = 0, expInvalid = 0;
        for (int i = 0; i < n; i++) { if (i % 97 == 96) expMissing++; else if (i % 89 == 88) expCorrupt++; else if (i % 211 == 210) expInvalid++; }
        System.out.printf("%nInjected into %,d files: missing=%d corrupt=%d invalid-mark=%d%n", n, expMissing, expCorrupt, expInvalid);
        System.out.printf("%6s %12s %10s %8s %14s %10s%n", "pool", "time (ms)", "speed-up", "total", "missing/corr/inv", "complete?");
        for (int io : new int[]{400, 0}) {
        SIMULATED_IO_MICROS = io;
        System.out.println(io > 0 ? "A) with 400 us simulated network-share latency per file (I/O-bound):"
                                  : "B) local disk, no simulated latency (CPU-bound, " + Runtime.getRuntime().availableProcessors() + " cores):");
        double base = 0;
        for (int p : new int[]{1, 2, 4, 8, 16, 32}) {
            long[] t = new long[3]; Map<String, Outcome> r = null;
            for (int k = 0; k < 3; k++) { long t0 = System.nanoTime(); r = process(all, Path.of("data/big"), p); t[k] = System.nanoTime() - t0; }
            java.util.Arrays.sort(t);
            double ms = t[1] / 1e6;
            if (p == 1) base = ms;
            Map<Status, Integer> sm = summary(r);
            boolean complete = r.size() == n && sm.values().stream().mapToInt(Integer::intValue).sum() == n
                    && sm.get(Status.MISSING_FILE) == expMissing && sm.get(Status.CORRUPT_FILE) == expCorrupt && sm.get(Status.INVALID_MARKS) == expInvalid
                    && sm.get(Status.UNEXPECTED) == 0 && sm.get(Status.TIMEOUT) == 0;
            System.out.printf("%6d %12.0f %9.1fx %8d %14s %10s%n", p, ms, base / ms, r.size(),
                    sm.get(Status.MISSING_FILE) + "/" + sm.get(Status.CORRUPT_FILE) + "/" + sm.get(Status.INVALID_MARKS), complete ? "yes" : "NO");
        }
        }
        SIMULATED_IO_MICROS = 400;
        alternatives(all);
    }

    // ------------------------------------------------------------ alternatives
    static void alternatives(List<String> all) throws Exception {
        Path dir = Path.of("data/big");
        var mx = ManagementFactory.getThreadMXBean();
        System.out.println("\nAlternatives on the same 10,000 files:");

        mx.resetPeakThreadCount();
        long t0 = System.nanoTime();
        Map<String, Outcome> ps = new ConcurrentHashMap<>();
        all.parallelStream().forEach(r -> ps.put(r, new ScoreTask(dir.resolve(r + ".txt"), r).call()));
        System.out.printf("   parallelStream (common pool, %d workers): %6.0f ms, peak JVM threads %d, outcomes %d%n",
                java.util.concurrent.ForkJoinPool.commonPool().getParallelism(), (System.nanoTime() - t0) / 1e6, mx.getPeakThreadCount(), ps.size());

        mx.resetPeakThreadCount();
        t0 = System.nanoTime();
        Map<String, Outcome> tpf = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>(all.size());
        for (String r : all) {
            Thread th = new Thread(() -> tpf.put(r, new ScoreTask(dir.resolve(r + ".txt"), r).call()));
            threads.add(th); th.start();
        }
        for (Thread th : threads) th.join();
        System.out.printf("   one thread per file (10,000 threads)  : %6.0f ms, peak JVM threads %d, outcomes %d%n",
                (System.nanoTime() - t0) / 1e6, mx.getPeakThreadCount(), tpf.size());

        mx.resetPeakThreadCount();
        t0 = System.nanoTime();
        Map<String, Outcome> ex = process(all, dir, 16);
        System.out.printf("   ExecutorService fixed pool of 16      : %6.0f ms, peak JVM threads %d, outcomes %d%n",
                (System.nanoTime() - t0) / 1e6, mx.getPeakThreadCount(), ex.size());

        AtomicInteger escaped = new AtomicInteger();
        try {
            all.subList(0, 50).parallelStream().forEach(r -> { if (r.endsWith("17")) throw new IllegalStateException("bug in " + r); });
        } catch (IllegalStateException e) { escaped.incrementAndGet(); }
        System.out.println("   failure handling: an exception thrown inside parallelStream().forEach aborts the whole stream ("
                + (escaped.get() == 1 ? "observed" : "not observed") + "); ExecutorService confines it to that file's Future.");
    }
}
