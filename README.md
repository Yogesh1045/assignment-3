# Programming in Java: Assignment 3 (CO3)
## Exception Handling and Multithreading

| | |
|---|---|
| **Student A (name / register number)** | Yogesh / _(add your register number)_ |
| **Student B (name / register number)** | _(partner's name / register number)_ |
| **Course** | CSA09 – Programming in Java |
| **Java version** | OpenJDK 21 (Java 17 or newer needed) |

> Pair-work note: this repository contains **every question executed in full**, including both the Student A and Student B parts, as the assignment requires each student to run everything in their own repository. Each question's README labels the Student A and Student B sections.

## How to run
```bash
./run_all.sh          # compiles and runs everything; outputs in Qn/output/ (≈ 3 minutes)
# or one question, e.g.
cd Q6 && javac *.java && java TokenCounter && java MessAndWallets
```
Thread timings vary from run to run and machine to machine; the trends and correctness checks are what matter.

## Results
| Q | Topic | Marks | Result |
|---|---|---|---|
| [Q1](Q1) | ATM: try / multi-catch / finally | 6 | 8 failure points classified; 8 test paths all reach `finally`. Wrong catch order rejected by the compiler. Exception ≈ 469 ns vs error code ≈ 7 ns. |
| [Q2](Q2) | Custom exceptions and chaining | 7 | **Complete the Code output matches.** Three-layer portal, 6 scenarios. Chained trace shown, and what is lost without the cause. Trace capture is O(depth). |
| [Q3](Q3) | try-with-resources | 7 | Manual close leaked **50 files in 50 failed runs**; try-with-resources leaked 0. Same report on good input. Suppressed exceptions and close order demonstrated. |
| [Q4](Q4) | Thread creation and life cycle | 7 | 8 different finish orders in 10 runs; priorities had no reliable effect; daemon killed when main ended. `run()` runs on main. All 6 states observed. |
| [Q5](Q5) | Seat-booking race | 6 | **Complete the Code: 10 seats.** Race reproduced in 20/20 trials (up to 100 sold for 10 seats). Block lock ≈ 15% faster than method lock; CAS fastest. |
| [Q6](Q6) | Deadlock and producer–consumer | 7 | **Complete the Code: 210.** Deadlock 10/10, fixed by lock ordering. `if` gave count −488; `notify()` hung 10/10, `notifyAll()` 0/10. |
| [Q7](Q7) | Fault-tolerant concurrent processor | 10 | ExecutorService + Callable + Future + ConcurrentHashMap. 10,000 files with 261 bad ones: always complete and correct; 5.3 s → 0.64 s with 8 threads. |

## Individual contribution record
| Question | Student A – primary responsibility | Student B – primary responsibility | Joint |
|---|---|---|---|
| Q1 | Exception design (hierarchy, table, catch order, finally) | Testing and tracing | failure points, implementation, design question |
| Q2 | Hierarchy and propagation design | Failure scenarios and chaining evidence | hierarchy, layers, conclusion |
| Q3 | Debugging and experimental analysis | Design and correctness | both versions, engineering decision |
| Q4 | Thread design | Behavioural experiments | implementation, conclusion |
| Q5 | Producing and explaining the race | Comparing synchronization strategies | implementations, deployment decision |
| Q6 | Producer–consumer design | Deadlock analysis and prevention | both parts, conclusion |
| Q7 | Stage 2: design | Stage 3: implement and measure | Stages 1 and 4 |
