import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Q2 - Online examination portal: user-defined exception hierarchy across
 * Controller -> Service -> Storage, with chaining.
 *
 *   Exception
 *    └─ PortalException (checked)                    - any rule that rejects a submission
 *        ├─ ExamWindowClosedException                - service layer
 *        ├─ NotRegisteredException                   - service layer
 *        └─ PortalStorageException                   - storage layer, wraps IOException
 *            └─ AnswerFileTooLargeException          - storage layer, size limit
 *   RuntimeException
 *    └─ PortalConfigurationException (unchecked)     - programming/config bug (e.g. null course table)
 */
public class ExamPortal {

    // ---------------- exception hierarchy ----------------
    static class PortalException extends Exception {
        PortalException(String m) { super(m); }
        PortalException(String m, Throwable c) { super(m, c); }
    }
    static class ExamWindowClosedException extends PortalException {
        ExamWindowClosedException(String m) { super(m); }
    }
    static class NotRegisteredException extends PortalException {
        NotRegisteredException(String m) { super(m); }
    }
    static class PortalStorageException extends PortalException {
        PortalStorageException(String m, Throwable c) { super(m, c); }
    }
    static class AnswerFileTooLargeException extends PortalStorageException {
        final long sizeKb, limitKb;
        AnswerFileTooLargeException(String m, long size, long limit) { super(m, null); sizeKb = size; limitKb = limit; }
    }
    static class PortalConfigurationException extends RuntimeException {
        PortalConfigurationException(String m) { super(m); }
    }

    // ---------------- storage layer ----------------
    static class StorageLayer {
        static final long LIMIT_KB = 500;
        boolean diskFull;
        boolean keepCause = true;                                  // switched off to show what is lost
        void store(String student, String file, long sizeKb) throws PortalStorageException {
            if (sizeKb > LIMIT_KB)
                throw new AnswerFileTooLargeException(file + " is " + sizeKb + " KB; limit is " + LIMIT_KB + " KB", sizeKb, LIMIT_KB);
            try {
                writeToDisk(file, sizeKb);
            } catch (IOException e) {
                if (keepCause) throw new PortalStorageException("Could not store " + file + " for " + student, e);
                throw new PortalStorageException("Could not store " + file + " for " + student, null);   // cause dropped
            }
        }
        private void writeToDisk(String file, long sizeKb) throws IOException {
            if (diskFull) throw new IOException("No space left on device (/exam-store, 0 bytes free)");
        }
    }

    // ---------------- service layer ----------------
    static class SubmissionService {
        final StorageLayer storage;
        final Map<String, Set<String>> registrations;
        final LocalDateTime windowCloses;
        SubmissionService(StorageLayer s, Map<String, Set<String>> reg, LocalDateTime closes) {
            storage = s; registrations = reg; windowCloses = closes;
        }
        void submit(String student, String course, String file, long sizeKb, LocalDateTime now) throws PortalException {
            if (registrations == null) throw new PortalConfigurationException("Registration table not loaded");
            if (now.isAfter(windowCloses))
                throw new ExamWindowClosedException("Exam window for " + course + " closed at " + windowCloses.toLocalTime());
            Set<String> courses = registrations.get(student);
            if (courses == null || !courses.contains(course))
                throw new NotRegisteredException(student + " is not registered for " + course);
            storage.store(student, file, sizeKb);                  // propagates PortalStorageException
        }
    }

    // ---------------- controller layer: the only place that talks to the student ----------------
    static class SubmissionController {
        final SubmissionService service;
        SubmissionController(SubmissionService s) { service = s; }
        String handle(String student, String course, String file, long sizeKb, LocalDateTime now) {
            try {
                service.submit(student, course, file, sizeKb, now);
                return "Submitted successfully.";
            } catch (ExamWindowClosedException e) {
                return "Submission rejected: the exam window has closed.";
            } catch (NotRegisteredException e) {
                return "Submission rejected: you are not registered for this course.";
            } catch (AnswerFileTooLargeException e) {
                return "Submission rejected: your file is " + e.sizeKb + " KB (maximum " + e.limitKb + " KB). Please compress it.";
            } catch (PortalStorageException e) {
                log(e);                                            // full chain goes to the server log only
                return "We could not save your answers right now. Please try again in a few minutes.";
            } catch (PortalException e) {
                return "Submission rejected: " + e.getMessage();
            }
        }
        static void log(Throwable e) {
            System.out.println("    [server log] " + e);
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) System.out.println("    [server log]   caused by: " + c);
        }
    }

    public static void main(String[] args) {
        LocalDateTime closes = LocalDateTime.of(2026, 11, 20, 12, 0);
        LocalDateTime before = closes.minusMinutes(30), after = closes.plusMinutes(5);
        StorageLayer storage = new StorageLayer();
        SubmissionService service = new SubmissionService(storage,
                Map.of("192311001", Set.of("CSA09"), "192311002", Set.of("UBA10")), closes);
        SubmissionController ui = new SubmissionController(service);

        System.out.println("Scenario table (one per exception):");
        Object[][] sc = {
            {"S1 valid submission",         "192311001", "CSA09", 200L, before, false},
            {"S2 window closed",            "192311001", "CSA09", 200L, after,  false},
            {"S3 not registered",           "192311002", "CSA09", 200L, before, false},
            {"S4 file too large",           "192311001", "CSA09", 900L, before, false},
            {"S5 disk full (IOException)",  "192311001", "CSA09", 200L, before, true},
        };
        for (Object[] s : sc) {
            storage.diskFull = (Boolean) s[5];
            System.out.println("  " + s[0]);
            System.out.println("    student sees: " + ui.handle((String) s[1], (String) s[2], "ans.pdf", (Long) s[3], (LocalDateTime) s[4]));
        }
        storage.diskFull = false;
        System.out.println("  S6 configuration bug (unchecked)");
        try {
            new SubmissionController(new SubmissionService(storage, null, closes)).handle("x", "CSA09", "a.pdf", 1, before);
        } catch (PortalConfigurationException e) {
            System.out.println("    not caught by the controller (unchecked, a bug): " + e);
        }

        System.out.println("\nChained stack trace (S5, cause kept):");
        storage.diskFull = true;
        try { service.submit("192311001", "CSA09", "ans.pdf", 200, before); }
        catch (PortalException e) {
            e.printStackTrace(System.out);
            System.out.println("getCause() -> " + e.getCause());
        }

        System.out.println("\nSame failure re-thrown WITHOUT the cause:");
        storage.keepCause = false;
        try { service.submit("192311001", "CSA09", "ans.pdf", 200, before); }
        catch (PortalException e) {
            e.printStackTrace(System.out);
            System.out.println("getCause() -> " + e.getCause() + "   <- the real reason (disk full) is gone");
        }

        traceCost();
    }

    /** Alternative concept: cost of capturing a stack trace, and memory held by a chain. */
    static void traceCost() {
        System.out.println("\nCost of creating one exception at call depth d (median of 5 x 200,000, ns):");
        System.out.printf("%8s %18s %22s %14s%n", "depth d", "with stack trace", "without (writable=false)", "frames held");
        for (int d : new int[]{1, 10, 50, 200}) {
            double[] a = new double[5], b = new double[5];
            int[] frames = new int[1];
            for (int r = -2; r < 5; r++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 200_000; i++) frames[0] = deep(d, true).getStackTrace().length * 0 + deepCount(d, true);
                long t1 = System.nanoTime();
                for (int i = 0; i < 200_000; i++) deepCount(d, false);
                long t2 = System.nanoTime();
                if (r >= 0) { a[r] = (t1 - t0) / 200_000.0; b[r] = (t2 - t1) / 200_000.0; }
            }
            java.util.Arrays.sort(a); java.util.Arrays.sort(b);
            System.out.printf("%8d %18.0f %22.0f %14d%n", d, a[2], b[2], deep(d, true).getStackTrace().length);
        }
        Throwable root = new IOException("disk");
        Throwable mid = new PortalStorageException("store", root);
        Throwable top = new RuntimeException("ui", mid);
        int total = 0; for (Throwable t = top; t != null; t = t.getCause()) total += t.getStackTrace().length;
        System.out.println("A 3-level chain keeps all 3 exception objects and " + total + " stack frames reachable from the top one.");
    }

    static Throwable deep(int d, boolean trace) { return d == 0 ? make(trace) : deep(d - 1, trace); }
    static int deepCount(int d, boolean trace) { return d == 0 ? (make(trace).getMessage().length()) : deepCount(d - 1, trace); }
    static Throwable make(boolean trace) {
        return trace ? new PortalException("x") : new PortalException("x") {
            @Override public synchronized Throwable fillInStackTrace() { return this; }   // skip capture
        };
    }
}
