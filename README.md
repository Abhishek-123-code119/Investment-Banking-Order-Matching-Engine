# 📈 Investment Banking Order Matching Engine

A production-inspired, multi-threaded order matching engine in pure Java.
**No Maven. No Gradle. No external dependencies.** Just `javac` and `java`.

---

## 🚀 Quick Start

```bash
# 1. Build
./build.sh

# 2. Run scripted demo
./run.sh

# 3. Run stress test (500 concurrent orders, 4 threads)
./run.sh stress

# 4. Run interactive CLI
./run.sh interactive

# 5. Run all tests
./test.sh
```

> Requires Java 17+. Check with: `java -version`

---

## 🎮 Interactive CLI Commands

```
buy  <SYMBOL> <PRICE> <QTY>    e.g.  buy AAPL 150.00 100
sell <SYMBOL> <PRICE> <QTY>    e.g.  sell AAPL 149.50 200
mkt  <SYMBOL> buy|sell <QTY>   e.g.  mkt AAPL buy 50
cancel <SYMBOL> <ORDER_ID>     e.g.  cancel AAPL A1B2C3D4
book <SYMBOL>                  e.g.  book AAPL
stats <SYMBOL>                 e.g.  stats AAPL
quit
```

---

## 🗂️ Project Structure

```
OrderMatchingEngine/
├── build.sh                        ← Compile everything
├── run.sh                          ← Run demo / stress / interactive
├── test.sh                         ← Run all tests
├── out/                            ← Compiled .class files (generated)
├── src/
│   └── com/jpmc/ome/
│       ├── model/
│       │   ├── Order.java          ← Entity: AtomicLong seq, volatile status
│       │   └── Trade.java          ← Executed trade record
│       ├── engine/
│       │   ├── OrderBook.java      ← Core DSA: heaps + hashmap + RW lock
│       │   └── MatchingEngine.java ← Multi-threaded dispatcher
│       ├── service/
│       │   ├── MarketDataService.java  ← VWAP, volume, high/low
│       │   └── RiskManager.java        ← Pre-trade risk checks
│       └── cli/
│           └── OMEDemo.java        ← Demo, stress test, interactive CLI
└── test/
    └── com/jpmc/ome/
        └── TestRunner.java         ← 16 tests, zero dependencies
```

---

## 🧠 DSA & System Design

| Concept | Where | Complexity |
|---|---|---|
| **Max-Heap** | Buy-side order book | O(log n) insert/remove |
| **Min-Heap** | Sell-side order book | O(log n) insert/remove |
| **HashMap** | Order index for cancellations | O(1) lookup |
| **Lazy Deletion** | Cancelled orders skipped on pop | Avoids O(n) heap rebuild |
| **Price-Time Priority** | Sequence # tiebreaker in comparator | FIFO fairness at same price |
| **ReentrantReadWriteLock** | Per-book lock | High read throughput |
| **BlockingQueue** | Order ingestion | Producer/consumer decoupling |
| **Per-symbol Executors** | Parallel matching across symbols | No cross-symbol locking |
| **CopyOnWriteArrayList** | Trade log | Lock-free reads |

---

