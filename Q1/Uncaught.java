/** Q1 Student B: an exception nobody catches - the JVM's default handler prints the trace and exits with code 1. */
public class Uncaught {
    public static void main(String[] args) { new Uncaught().dispense("12O0"); }   // letter O, not zero
    void dispense(String amountText) { System.out.println("Dispensing " + parse(amountText)); }
    int parse(String s) { return Integer.parseInt(s); }
}
