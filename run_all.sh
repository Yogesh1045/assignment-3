#!/usr/bin/env bash
# Compiles and runs every question; output saved to Qn/output/<Program>.txt. Needs JDK 17+.
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS   # silence "Picked up ..." JVM notices
ROOT="$(cd "$(dirname "$0")" && pwd)"
run() { local q="$1" main="$2" cp="$3"; shift 3; mkdir -p "$ROOT/$q/output"; echo "== $q : $main"
  ( cd "$ROOT/$q" && java -cp "$cp" "$main" "$@" ) 2>&1 | grep -v '^Picked up' | tee "$ROOT/$q/output/$main.txt"; echo; }
build() { rm -rf "$ROOT/out/$1"; mkdir -p "$ROOT/out/$1"; javac -d "$ROOT/out/$1" "$ROOT/$1"/*.java 2>&1 | grep -v '^Picked up'; }
for q in Q1 Q2 Q3 Q4 Q5 Q6 Q7; do build $q; done
# Q1
run Q1 AtmTests "$ROOT/out/Q1"
run Q1 Uncaught "$ROOT/out/Q1"
( cd "$ROOT/Q1" && echo '$ javac wrongorder/WrongOrder.java' && javac -d /tmp/wo wrongorder/WrongOrder.java ) 2>&1 | grep -v '^Picked up' | tee "$ROOT/Q1/output/WrongOrder.txt"
( cd "$ROOT/Q1" && echo '$ echo "1234 2000" | java Atm cardreader.txt' && echo "1234 2000" | java -cp "$ROOT/out/Q1" Atm cardreader.txt ) 2>&1 | grep -v '^Picked up' | tee "$ROOT/Q1/output/Atm.txt"
# Q2
run Q2 StorageDemo "$ROOT/out/Q2"
run Q2 ExamPortal "$ROOT/out/Q2"
# Q3
run Q3 SalesReport "$ROOT/out/Q3"
# Q4
run Q4 ScannerStations "$ROOT/out/Q4"
# Q5: given program without synchronized, 10 runs; then with synchronized
mkdir -p "$ROOT/out/Q5u"; javac -d "$ROOT/out/Q5u" "$ROOT/Q5/unsafe/SeatBooking.java" 2>&1 | grep -v '^Picked up'
mkdir -p "$ROOT/Q5/output"
( for i in $(seq 1 10); do printf "run %2d: " $i; java -cp "$ROOT/out/Q5u" SeatBooking; done ) 2>&1 | grep -v '^Picked up' | tee "$ROOT/Q5/output/SeatBooking_unsynchronized.txt"
( for i in $(seq 1 10); do printf "run %2d: " $i; java -cp "$ROOT/out/Q5" SeatBooking; done ) 2>&1 | grep -v '^Picked up' | tee "$ROOT/Q5/output/SeatBooking.txt"
run Q5 BookingLab "$ROOT/out/Q5"
# Q6
mkdir -p "$ROOT/Q6/output"
( for i in 1 2 3 4 5; do java -cp "$ROOT/out/Q6" TokenCounter; done ) 2>&1 | grep -v '^Picked up' | tee "$ROOT/Q6/output/TokenCounter.txt"
run Q6 MessAndWallets "$ROOT/out/Q6"
# Q7
run Q7 ResultProcessor "$ROOT/out/Q7"
