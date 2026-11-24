# Q3: Resource Safety, Debugging and Robust Code

**Program:** `SalesReport.java`: version 1 `manual()` (try–catch–finally, manual `close()`), version 2 `withResources()` (try-with-resources), plus experiments. It generates its own input files in `work/`.
**Run:** `javac SalesReport.java && java SalesReport` · **Output:** `output/SalesReport.txt` / `.png`

## Shared work
- **Same report:** with 5 good store files, both versions produce byte-identical `summary.txt` (`identical report files: true`, total 13,875).
- Every `FileReader`/`FileWriter` is wrapped in `TrackedReader`/`TrackedWriter`, which count the files currently open. This is the evidence for leaks. Real OS descriptors are also counted from `/proc/self/fd`.

## Student A: debugging and experimental analysis
- **Locating the leak (breakpoints, step-through):** breakpoint on `storeTotal += Long.parseLong(...)` in `manual()`, with a conditional breakpoint `line.contains("O")`. Step over: the `NumberFormatException` jumps straight to `finally`, which closes only `w`. Stepping shows `r.close()` (the line marked *LEAK POINT*) is **never executed** for that store. The `openNow` watch stays at 1 after the method exits.
- **Failing input:** 200 store files, where **store #3 has a corrupt line** `item51,12O` (letter O). The report fails part-way: stores 1–2 have been read, and the summary file holds only 2 lines, so it is **incomplete**.
- **Files left open, same input, job retried 50 times (as a scheduler would):**

| Version | runs failed | tracked files still open | extra OS descriptors |
|---|---|---|---|
| manual close | 50 | **50** (one leaked reader per run) | +26 (the rest were reclaimed only when the GC happened to run the cleaner) |
| try-with-resources | 50 | **0** | −26 (the GC released leftovers from the previous version) |

  At 50 leaks per 50 runs, a typical limit of 1,024 descriptors is exhausted after about 1,000 failed runs, giving **"Too many open files"**. Leaked handles are reclaimed only when (or if) the GC runs, which is nondeterministic.
- **Logging which file failed:** the robust version logs `[LOG SEVERE] Bad data in work/bad/store3.csv line 51: "item51,12O"` and throws `IOException("Corrupt store file work/bad/store3.csv at line 51")` with the parse error as its cause.

## Student B: design and correctness
- **Resources opened:** (1) one `FileWriter` (wrapped by `BufferedWriter`) for `summary.txt`, which must be closed after the last write, *also on failure*, so that buffered lines are flushed; (2) **one `FileReader` per store file** (wrapped by `BufferedReader`), which must be closed at the end of *that file's* iteration, success or failure. Closing the outer `Buffered*` wrapper closes the inner file.
- **Why the manual version leaks:** `r.close()` is an ordinary statement after the loop. An exception thrown by `readLine()` or `parseLong()` transfers control directly to the nearest handler or `finally`, **skipping every remaining statement**, including `close()`. Only the writer is in a `finally`. To be correct, each reader would need its own nested `try { … } finally { r.close(); }`, and `close()` itself can throw and hide the original exception.
- **How try-with-resources guarantees closing:** the compiler generates the nested try/finally for you. Every resource declared in `try (…)` that was **successfully opened** is closed when the block exits, by normal completion, `return`, `break` or exception, in **reverse order of opening** (last opened, first closed). Output: `open reader-A, open reader-B, close reader-B, close reader-A`.
- **Suppressed exception:** if the body throws *and* `close()` then throws too, the body's exception is the **primary** one (it is the real problem) and each close failure is attached to it with `addSuppressed()`, retrievable via `getSuppressed()`. Output: primary `body failed while reading`, suppressed `close of reader-B failed` and `close of reader-A failed`. With manual `finally { close(); }`, the close exception would *replace* the original, and the real cause would be lost.

## Joint engineering decision: when try-with-resources is not enough
1. **Partial output:** resources get closed, but a failure at store #3 still leaves a **half-written summary.txt** that looks valid. Fix: write to `summary.tmp`, then do an atomic `Files.move(..., ATOMIC_MOVE)` only on success, or write a clear FAILED marker.
2. **Policy for bad data:** stop at the first corrupt store, or skip it, log it and continue? try-with-resources does not decide this. A robust job catches per-store errors, records them and still produces a report flagged "incomplete: 1 store skipped".
3. **Resources that are not `AutoCloseable`**, or that are opened in one method and closed in another (pooled connections, files kept open across calls), need explicit life-cycle management.
4. **Many files open at once** (e.g. merging 10,000 files simultaneously) can still hit the OS limit even without leaks, so the number open at one time must be bounded.
5. **Close failures that matter** (e.g. flush to a full disk on `close()`): they are suppressed only if the body also failed; otherwise they are thrown and must still be handled.

## Alternative concept and complexity (f store files, failure at store k)
```java
// (a) explicit close in finally - correct but verbose
for (String f : files) {
    BufferedReader r = null;
    try { r = new BufferedReader(new FileReader(f)); /* read */ }
    finally { if (r != null) try { r.close(); } catch (IOException e) { /* log, don't hide the original */ } }
}
// (b) no closing at all
for (String f : files) { BufferedReader r = new BufferedReader(new FileReader(f)); /* read, never close */ }
```
| Version | Processing time | Max file handles open at the failure | After R repeated failing runs |
|---|---|---|---|
| try-with-resources | O(total lines) | **2** (writer + the current reader) | 0 leaked |
| explicit `finally` close | O(total lines) | **2** | 0 leaked (if written correctly) |
| original manual close | O(total lines) | 2, plus 1 leaked reader per failure | **R** leaked (measured: 50 after 50 runs) |
| no closing | O(total lines) | **k + 1** (every reader opened so far, plus the writer) | **R·(k+1)**; up to f + 1 per successful run |

Processing time is the same, O(total lines): closing is O(1) per file. The difference is entirely in **space**: the peak number of handles, which is O(1) for the closing versions and O(f) without closing.
