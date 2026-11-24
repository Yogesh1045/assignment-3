import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Q3 - Daily sales report: reads f store files (one "item,amount" per line), writes summary.txt.
 * Version 1: try-catch-finally with manual close (as originally written - leaks on failure).
 * Version 2: try-with-resources.
 * Every Reader/Writer is wrapped in a Tracked* class that counts how many are currently open,
 * and the process's real open file descriptors are counted from /proc/self/fd (Linux).
 */
public class SalesReport {

    static final Logger LOG = Logger.getLogger("SalesReport");
    static int openNow = 0;                                    // files currently open (tracked)

    static class TrackedReader extends FileReader {
        final String name;
        TrackedReader(String f) throws IOException { super(f); name = f; openNow++; }
        @Override public void close() throws IOException { super.close(); openNow--; }
    }
    static class TrackedWriter extends FileWriter {
        TrackedWriter(String f) throws IOException { super(f); openNow++; }
        @Override public void close() throws IOException { super.close(); openNow--; }
    }

    // ------------------------------------------------------------ version 1: manual close
    /** Original style: each reader closed at the END of its loop iteration - skipped if parsing throws. */
    static long manual(List<String> files, String out) throws IOException {
        Writer w = null;
        long total = 0;
        try {
            w = new BufferedWriter(new TrackedWriter(out));
            for (String f : files) {
                BufferedReader r = new BufferedReader(new TrackedReader(f));    // resource opened
                String line;
                long storeTotal = 0;
                while ((line = r.readLine()) != null) {
                    storeTotal += Long.parseLong(line.split(",")[1].trim());     // may throw -> close() below is skipped
                }
                r.close();                                                       // LEAK POINT: never reached on exception
                w.write(f + "," + storeTotal + "\n");
                total += storeTotal;
            }
            w.write("TOTAL," + total + "\n");
        } finally {
            if (w != null) w.close();                                           // only the writer is protected
        }
        return total;
    }

    // ------------------------------------------------------------ version 2: try-with-resources
    static long withResources(List<String> files, String out) throws IOException {
        long total = 0;
        try (Writer w = new BufferedWriter(new TrackedWriter(out))) {
            for (String f : files) {
                long storeTotal = 0;
                try (BufferedReader r = new BufferedReader(new TrackedReader(f))) {
                    String line;
                    int lineNo = 0;
                    while ((line = r.readLine()) != null) {
                        lineNo++;
                        try {
                            storeTotal += Long.parseLong(line.split(",")[1].trim());
                        } catch (RuntimeException e) {
                            LOG.severe("Bad data in " + f + " line " + lineNo + ": \"" + line + "\"");   // which file failed
                            throw new IOException("Corrupt store file " + f + " at line " + lineNo, e);
                        }
                    }
                }                                                                  // r closed here, always
                w.write(f + "," + storeTotal + "\n");
                total += storeTotal;
            }
            w.write("TOTAL," + total + "\n");
        }                                                                          // w closed here, always
        return total;
    }

    // ------------------------------------------------------------ experiments
    static long fdCount() {
        try (var s = Files.list(Path.of("/proc/self/fd"))) { return s.count(); }
        catch (IOException e) { return -1; }
    }

    static List<String> makeStores(Path dir, int f, int badAt) throws IOException {
        Files.createDirectories(dir);
        List<String> files = new ArrayList<>();
        for (int i = 1; i <= f; i++) {
            Path p = dir.resolve("store" + i + ".csv");
            StringBuilder sb = new StringBuilder();
            for (int k = 1; k <= 50; k++) sb.append("item").append(k).append(',').append(i * 10 + k).append('\n');
            if (i == badAt) sb.append("item51,12O\n");                 // letter O: corrupt line
            Files.writeString(p, sb.toString());
            files.add(p.toString());
        }
        return files;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "    [LOG %4$s] %5$s%n");
        Path dir = Path.of("work");

        System.out.println("1) Good input, 5 stores: both versions must give the same report");
        List<String> good = makeStores(dir.resolve("good"), 5, -1);
        long a = manual(good, "work/summary_manual.txt");
        long b = withResources(good, "work/summary_twr.txt");
        boolean same = Files.readString(Path.of("work/summary_manual.txt")).equals(Files.readString(Path.of("work/summary_twr.txt")));
        System.out.println("   manual total=" + a + "  try-with-resources total=" + b + "  identical report files: " + same);
        System.out.println(Files.readString(Path.of("work/summary_twr.txt")).indent(6));

        System.out.println("2) Failing input: 200 stores, store #3 corrupt; the report job is retried 50 times (like a scheduler would)");
        List<String> bad = makeStores(dir.resolve("bad"), 200, 3);
        for (String version : new String[]{"manual", "try-with-resources"}) {
            openNow = 0;
            long fd0 = fdCount();
            int failures = 0;
            for (int run = 0; run < 50; run++) {
                try {
                    if (version.equals("manual")) manual(bad, "work/s.txt"); else withResources(bad, "work/s.txt");
                } catch (IOException | RuntimeException e) {
                    failures++;
                    if (run == 0) System.out.println("   " + version + " first failure: " + e);
                }
            }
            System.out.printf("   %-19s runs failed=%d  tracked files still open=%d  extra OS file descriptors=%d%n",
                    version, failures, openNow, fdCount() - fd0);
        }

        System.out.println("\n3) Suppressed exception: body throws AND close() throws");
        try (Faulty x = new Faulty("reader-A"); Faulty y = new Faulty("reader-B")) {
            throw new IllegalStateException("body failed while reading");
        } catch (IllegalStateException e) {
            System.out.println("   primary   : " + e.getMessage());
            for (Throwable s : e.getSuppressed()) System.out.println("   suppressed: " + s.getMessage());
        }
    }

    /** Resource whose close() always fails - prints the close order. */
    static class Faulty implements AutoCloseable {
        final String n;
        Faulty(String n) { this.n = n; System.out.println("   open  " + n); }
        @Override public void close() throws IOException {
            System.out.println("   close " + n);
            throw new IOException("close of " + n + " failed");
        }
    }
}
