import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.InputMismatchException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Q1 - ATM withdrawal with try / multi-catch / finally.
 *
 * Failure points:
 *   F1 card-reader file missing           -> FileNotFoundException  (checked)
 *   F2 card-reader file unreadable        -> IOException            (checked)
 *   F3 letters typed for a number         -> InputMismatchException (unchecked)
 *   F4 input ends early (Ctrl+D)          -> NoSuchElementException (unchecked)
 *   F5 unknown account number             -> IllegalArgumentException (unchecked)
 *   F6 negative / zero / non-multiple-of-100 amount -> IllegalArgumentException (unchecked)
 *   F7 amount greater than balance        -> InsufficientFundsException (checked, user-defined)
 *   F8 JVM out of memory / stack overflow -> Error (not caught)
 *
 * Usage: java Atm <cardReaderFile>  (account and amount read from standard input)
 */
public class Atm {

    static class InsufficientFundsException extends Exception {
        InsufficientFundsException(String msg) { super(msg); }
    }

    static final Map<Long, Double> BALANCES = new HashMap<>(Map.of(1234L, 5000.0, 9876L, 300.0));
    static int machineCash = 20_000;

    /** Business rule check. Throws checked or unchecked exceptions; catches nothing itself. */
    static double withdraw(long account, int amount) throws InsufficientFundsException {
        Double balance = BALANCES.get(account);
        if (balance == null) throw new IllegalArgumentException("Unknown account " + account);
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive: " + amount);
        if (amount % 100 != 0) throw new IllegalArgumentException("Amount must be a multiple of 100: " + amount);
        if (amount > balance) throw new InsufficientFundsException("Balance " + balance + " < requested " + amount);
        BALANCES.put(account, balance - amount);
        machineCash -= amount;
        return balance - amount;
    }

    /** Reads the card-reader file: first line = "<pin> <account>". */
    static long readCard(String path) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            if (line == null) throw new IOException("Card-reader file is empty");
            return Long.parseLong(line.trim().split("\\s+")[1]);
        }
    }

    /** One ATM session. Returns a short code describing the path taken (used by AtmTests). */
    static String session(String cardFile, Scanner in) {
        String path = "?";
        System.out.println("  --- session start (card file: " + cardFile + ") ---");
        try {
            long cardAccount = readCard(cardFile);                    // F1, F2
            System.out.print("  Enter account number: ");
            long account = in.nextLong();                             // F3, F4
            if (account != cardAccount) throw new IllegalArgumentException("Account does not match card");
            System.out.print("  Enter amount: ");
            int amount = in.nextInt();                                // F3, F4
            double left = withdraw(account, amount);                  // F5, F6, F7
            System.out.println("  Dispensed Rs " + amount + ". Balance left: Rs " + left);
            path = "SUCCESS";
        } catch (FileNotFoundException e) {                           // narrower checked first
            System.out.println("  [catch FileNotFoundException] Card reader unavailable: " + e.getMessage());
            path = "FileNotFound";
        } catch (IOException e) {                                     // its superclass after it
            System.out.println("  [catch IOException] Card read error: " + e.getMessage());
            path = "IOException";
        } catch (InputMismatchException | IllegalArgumentException e) {   // multi-catch: bad user input
            System.out.println("  [multi-catch " + e.getClass().getSimpleName() + "] Invalid input: " + e.getMessage());
            path = e.getClass().getSimpleName();
        } catch (InsufficientFundsException e) {
            System.out.println("  [catch InsufficientFundsException] " + e.getMessage());
            path = "InsufficientFunds";
        } catch (NoSuchElementException e) {                          // after InputMismatchException (its subclass)
            System.out.println("  [catch NoSuchElementException] Input ended early");
            path = "NoInput";
        } finally {
            System.out.println("  [finally] card ejected, session logged (path=" + path + ")");
        }
        return path;
    }

    public static void main(String[] args) {
        String card = args.length > 0 ? args[0] : "cardreader.txt";
        session(card, new Scanner(System.in));
    }
}
