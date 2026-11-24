# Q1: Handling Failure in a Bank ATM Simulation

**Programs:** `Atm.java` (the ATM), `AtmTests.java` (test inputs, stack trace, error-code alternative), `Uncaught.java`, `wrongorder/WrongOrder.java` (does not compile, on purpose)
**Run:** `javac Atm.java AtmTests.java Uncaught.java && java AtmTests && java Uncaught` · `echo "1234 2000" | java Atm cardreader.txt` · `javac wrongorder/WrongOrder.java`
**Outputs:** `output/` (AtmTests, Atm, Uncaught, WrongOrder: `.txt` + `.png`)

## Shared work: failure points
| # | Failure point | Exception | Type | Caught where |
|---|---|---|---|---|
| F1 | Card-reader file missing | `FileNotFoundException` | checked | `session()`, 1st catch |
| F2 | Card file empty or unreadable | `IOException` | checked | `session()`, 2nd catch |
| F3 | Letters typed for account/amount | `InputMismatchException` | unchecked | multi-catch |
| F4 | Input ends early | `NoSuchElementException` | unchecked | 5th catch (after its subclass F3) |
| F5 | Unknown account / account ≠ card | `IllegalArgumentException` | unchecked | multi-catch |
| F6 | Negative, zero or non-multiple-of-100 amount | `IllegalArgumentException` | unchecked | multi-catch |
| F7 | Amount > balance | `InsufficientFundsException` (user-defined) | **checked** | 4th catch |
| F8 | JVM out of memory / stack overflow | `OutOfMemoryError`, `StackOverflowError` | **Error** | not caught: the program cannot recover |

## Student A: exception design
**Relevant part of the Throwable hierarchy:**
```
Throwable
├── Error ─────────────── OutOfMemoryError, StackOverflowError        (F8, never caught)
└── Exception
    ├── IOException ────── FileNotFoundException                     (F1, F2: checked)
    ├── InsufficientFundsException (ours)                            (F7: checked)
    └── RuntimeException                                             (unchecked)
        ├── IllegalArgumentException                                 (F5, F6)
        └── NoSuchElementException ── InputMismatchException         (F4, F3)
```
**Catch-block order:** a catch for a subclass must come **before** its superclass, because Java picks the *first* matching block. Hence `FileNotFoundException` before `IOException`, and `InputMismatchException` (in the multi-catch) before `NoSuchElementException`. The multi-catch `InputMismatchException | IllegalArgumentException` is allowed because neither is a subclass of the other, and both mean "bad user input", which gets the same message. Checked and unchecked groups are independent, so their relative order is a choice. We put specific, recoverable cases first.

**What `finally` guarantees:** the card is ejected and the session is logged **on every path**: success, each of the 5 catch paths, and even an uncaught `RuntimeException` or `Error` that escapes `session()`. The only exceptions are the JVM being killed or `System.exit()` being called inside the try. The test summary shows `finally=yes` for all 8 tests.

## Student B: testing and tracing
| Test | Input | Path / catch block | finally ran? |
|---|---|---|---|
| T1 valid | `1234 1500` | SUCCESS (no catch) | yes |
| T2 letters | `1234 abc` | multi-catch: `InputMismatchException` | yes |
| T3 negative | `1234 -500` | multi-catch: `IllegalArgumentException` | yes |
| T4 over balance | `1234 900000` | `InsufficientFundsException` | yes |
| T5 missing card file | `missing.txt` | `FileNotFoundException` | yes |
| T6 empty card file | `emptycard.txt` | `IOException` | yes |
| T7 input ends early | `1234` | `NoSuchElementException` | yes |
| T8 wrong account | `9876 100` | multi-catch: `IllegalArgumentException` | yes |

**Uncaught exception (`Uncaught.java`):**
```
Exception in thread "main" java.lang.NumberFormatException: For input string: "12O0"
    at java.base/java.lang.NumberFormatException.forInputString(NumberFormatException.java:67)
    at java.base/java.lang.Integer.parseInt(Integer.java:662)
    at java.base/java.lang.Integer.parseInt(Integer.java:778)
    at Uncaught.parse(Uncaught.java:5)
    at Uncaught.dispense(Uncaught.java:4)
    at Uncaught.main(Uncaught.java:3)
```
- Line 1: the thread that died (`main`), the exception class, and its message (the bad text: letter O instead of zero).
- Lines 2–4: frames inside the JDK, **most recent first**. `forInputString` builds the exception, thrown from `parseInt(String, radix)` (line 662), called by `parseInt(String)` (line 778).
- Lines 5–7: our code. `parse` (line 5) was called by `dispense` (line 4), which was called by `main` (line 3). Read bottom-up for the call order, top-down for where it failed. No frame had a handler, so the JVM's default handler printed this and ended the thread.

**Broader catch before narrower (`wrongorder/WrongOrder.java`):**
```
WrongOrder.java:12: error: exception FileNotFoundException has already been caught
```
For checked exceptions the compiler **rejects** the unreachable catch. (With unchecked types in a multi-catch it also refuses subclass/superclass pairs.) If it compiled, the narrow handler could never run.

## Joint design question: one `catch (Exception e)`?
**Acceptable only** at the outermost boundary of the program: a top-level "last resort" handler that logs the full exception, shows "Transaction could not be completed, please contact the bank", and keeps the ATM running for the next customer. This is in *addition* to the specific handlers, not instead of them.
**What is lost when it replaces them:** (1) different responses: "re-enter the amount" vs "insufficient funds" vs "card reader out of service" all become one vague message; (2) the compiler's help, since a new checked exception added later is silently swallowed; (3) bugs such as a `NullPointerException` get treated as user errors and hidden; (4) control over `Error`s if someone widens it to `Throwable`.

## Alternative concept: no exceptions, error codes
```java
if (!in.hasNextLong()) return ERR_FORMAT;             // check BEFORE reading
long acc = in.nextLong();
if (!BALANCES.containsKey(acc)) return ERR_ACCOUNT;
if (!in.hasNextInt()) return ERR_FORMAT;
int amt = in.nextInt();
if (amt <= 0) return ERR_NEGATIVE;
if (amt > balance) return ERR_FUNDS;                   // and the CALLER must check every code
```
- **Readability and number of checks:** every call site needs `if (code < 0)` tests: about 6 checks here, repeated by every caller up the chain, mixing error handling into the normal logic. With exceptions the normal path reads straight through, and each failure is handled once, in one place. Error codes also cannot carry detail (which account? what balance?) unless you add out-parameters, and **a forgotten check silently continues** with −1 as an amount.
- **Cost (measured, 1,000,000 failing withdrawals):**

| | ns per failure |
|---|---|
| return an error code | **≈ 7** |
| throw + catch, normal exception | **≈ 469** (stack-trace capture dominates) |
| throw + catch, no stack trace (`writableStackTrace=false`) | ≈ 9 |

  Time: creating a Throwable walks the stack, **O(d)** for call depth d. An error code is O(1). Space: one exception object plus a `StackTraceElement` array of d frames, versus one int. **Conclusion:** exceptions cost about 0.5 µs, which is irrelevant for an ATM that handles one customer per minute but would matter in a loop running millions of times per second. Use exceptions for exceptional cases, and validate expected user mistakes (e.g. with `hasNextInt()`) where convenient.
