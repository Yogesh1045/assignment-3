import java.io.FileWriter;
import java.io.IOException;
import java.util.Scanner;

/**
 * Q1 Student B - test inputs that each trigger a different path, an uncaught exception with
 * its stack trace, and the error-code alternative with a cost measurement.
 */
public class AtmTests {
    public static void main(String[] args) throws Exception {
        try (FileWriter w = new FileWriter("emptycard.txt")) { /* empty card file */ }
        String[][] tests = {
            {"T1 valid withdrawal",            "cardreader.txt", "1234 1500"},
            {"T2 letters instead of amount",   "cardreader.txt", "1234 abc"},
            {"T3 negative amount",             "cardreader.txt", "1234 -500"},
            {"T4 more than balance",           "cardreader.txt", "1234 900000"},
            {"T5 card-reader file missing",    "missing.txt",    "1234 100"},
            {"T6 card file empty",             "emptycard.txt",  "1234 100"},
            {"T7 input ends early",            "cardreader.txt", "1234"},
            {"T8 account not matching card",   "cardreader.txt", "9876 100"},
        };
        System.out.printf("%-34s %-22s%n", "Test", "path taken (finally always ran)");
        StringBuilder table = new StringBuilder();
        for (String[] t : tests) {
            System.out.println(t[0] + "   input: \"" + t[2] + "\"");
            String path = Atm.session(t[1], new Scanner(t[2]));
            table.append(String.format("%-34s %-28s finally=yes%n", t[0], path));
        }
        System.out.println("\nSummary\n" + table);

        System.out.println("Uncaught exception (no handler) - stack trace:");
        try {
            uncaughtDemo();
        } catch (NumberFormatException e) {
            e.printStackTrace(System.out);
        }

        errorCodeAlternative();
    }

    // Three-level call chain so the trace has several frames to explain.
    static void uncaughtDemo() { parseAmount("12O0"); }        // letter O instead of zero
    static int parseAmount(String s) { return Integer.parseInt(s); }

    // ---------- Alternative: no exceptions, error codes ----------
    static final int ERR_FORMAT = -1, ERR_NEGATIVE = -2, ERR_FUNDS = -3;

    static int withdrawCode(String amountText, double balance) {
        for (int i = 0; i < amountText.length(); i++)
            if (!Character.isDigit(amountText.charAt(i)) && !(i == 0 && amountText.charAt(i) == '-')) return ERR_FORMAT;
        int amount = Integer.parseInt(amountText);
        if (amount <= 0) return ERR_NEGATIVE;
        if (amount > balance) return ERR_FUNDS;
        return amount;
    }

    static int withdrawThrow(String amountText, double balance) {
        int amount = Integer.parseInt(amountText);               // throws NumberFormatException
        if (amount <= 0) throw new IllegalArgumentException("negative");
        if (amount > balance) throw new IllegalStateException("funds");
        return amount;
    }

    static void errorCodeAlternative() {
        System.out.println("\nCost: 1,000,000 failing withdrawals (\"-500\"), median of 5 runs");
        long[] tCode = new long[5], tThrow = new long[5], tNoTrace = new long[5];
        long sink = 0;
        for (int r = -2; r < 5; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) sink += withdrawCode("-500", 1000);
            long t1 = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) {
                try { sink += withdrawThrow("-500", 1000); } catch (IllegalArgumentException e) { sink--; }
            }
            long t2 = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) {
                try { throw new LightException(); } catch (LightException e) { sink--; }
            }
            long t3 = System.nanoTime();
            if (r >= 0) { tCode[r] = t1 - t0; tThrow[r] = t2 - t1; tNoTrace[r] = t3 - t2; }
        }
        java.util.Arrays.sort(tCode); java.util.Arrays.sort(tThrow); java.util.Arrays.sort(tNoTrace);
        System.out.printf("  error code                 : %7.1f ns per failure%n", tCode[2] / 1e6);
        System.out.printf("  throw + catch (full trace) : %7.1f ns per failure%n", tThrow[2] / 1e6);
        System.out.printf("  throw + catch (no trace)   : %7.1f ns per failure%n", tNoTrace[2] / 1e6);
        Throwable t = new IllegalArgumentException("x");
        System.out.println("  frames captured in one exception here: " + t.getStackTrace().length);
        if (sink == 42) System.out.println();
    }

    /** An exception that skips stack-trace capture (writableStackTrace=false). */
    static class LightException extends RuntimeException {
        LightException() { super("light", null, false, false); }
    }
}
