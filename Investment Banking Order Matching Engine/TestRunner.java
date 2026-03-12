package com.jpmc.ome;

import com.jpmc.ome.engine.MatchingEngine;
import com.jpmc.ome.engine.OrderBook;
import com.jpmc.ome.model.Order;
import com.jpmc.ome.model.Trade;
import com.jpmc.ome.service.RiskManager;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-contained test runner — no JUnit required.
 * Uses simple assert helpers that throw on failure.
 * Run with: java -cp out com.jpmc.ome.TestRunner
 */
public class TestRunner {

    // ── Tiny assertion helpers ────────────────────────────────────────────────

    static void assertEquals(Object expected, Object actual, String msg) {
        if (!expected.equals(actual))
            fail(msg + " — expected: " + expected + ", got: " + actual);
    }
    static void assertTrue(boolean cond, String msg)  { if (!cond) fail(msg); }
    static void assertFalse(boolean cond, String msg) { if (cond) fail("Expected false: " + msg); }
    static void fail(String msg) { throw new AssertionError("FAIL: " + msg); }

    // ── Test registry ─────────────────────────────────────────────────────────

    static int passed = 0, failed = 0;

    static void run(String name, Runnable test) {
        try {
            test.run();
            System.out.printf("  ✅ %s%n", name);
            passed++;
        } catch (AssertionError | Exception e) {
            System.out.printf("  ❌ %s  →  %s%n", name, e.getMessage());
            failed++;
        }
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("""
            ╔══════════════════════════════════════════╗
            ║      Order Matching Engine — Tests       ║
            ╚══════════════════════════════════════════╝
            """);

        System.out.println("── OrderBook Unit Tests ─────────────────────");
        testOrderBook();

        System.out.println("\n── MatchingEngine Concurrency Tests ─────────");
        testConcurrency();

        System.out.println("\n── RiskManager Tests ────────────────────────");
        testRiskManager();

        System.out.printf("""
            %n════════════════════════════════════════════
            Results: %d passed, %d failed
            ════════════════════════════════════════════%n""", passed, failed);

        System.exit(failed > 0 ? 1 : 0);
    }

    // ── OrderBook Tests ───────────────────────────────────────────────────────

    static void testOrderBook() {
        run("No match when buy price < sell price", () -> {
            OrderBook book = new OrderBook("TEST");
            book.addOrder(new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 100));
            List<Trade> trades = book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 99.00, 100));
            assertTrue(trades.isEmpty(), "Should not match on spread");
        });

        run("Full match: equal quantity and price", () -> {
            OrderBook book = new OrderBook("TEST");
            book.addOrder(new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 100));
            List<Trade> trades = book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 100.00, 100));
            assertEquals(1, trades.size(), "Trade count");
            assertEquals(100, trades.get(0).getExecutedQuantity(), "Executed qty");
            assertEquals(100.00, trades.get(0).getExecutionPrice(), "Exec price");
        });

        run("Partial fill: buy qty > sell qty", () -> {
            OrderBook book = new OrderBook("TEST");
            Order sell = new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 50);
            Order buy  = new Order("TEST", Order.Side.BUY,  Order.Type.LIMIT, 100.00, 100);
            book.addOrder(sell);
            List<Trade> trades = book.addOrder(buy);
            assertEquals(1, trades.size(), "Trade count");
            assertEquals(50, trades.get(0).getExecutedQuantity(), "Exec qty");
            assertEquals(50, buy.getRemainingQty(), "Buy remaining");
            assertEquals(Order.Status.PARTIALLY_FILLED, buy.getStatus(), "Buy status");
            assertEquals(Order.Status.FILLED, sell.getStatus(), "Sell status");
        });

        run("Sweep multiple sell levels with one large buy", () -> {
            OrderBook book = new OrderBook("TEST");
            book.addOrder(new Order("TEST", Order.Side.SELL, Order.Type.LIMIT,  99.00, 60));
            book.addOrder(new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 60));
            List<Trade> trades = book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 100.00, 120));
            assertEquals(2, trades.size(), "Should produce 2 trades");
            int totalFilled = trades.stream().mapToInt(Trade::getExecutedQuantity).sum();
            assertEquals(120, totalFilled, "Total filled qty");
        });

        run("Price-time priority: earlier order fills first at same price", () -> {
            OrderBook book = new OrderBook("TEST");
            Order sell1 = new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 50);
            Order sell2 = new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 50);
            book.addOrder(sell1);
            book.addOrder(sell2);
            book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 100.00, 50));
            assertEquals(Order.Status.FILLED,  sell1.getStatus(), "sell1 should be filled first");
            assertEquals(Order.Status.PENDING, sell2.getStatus(), "sell2 should still be pending");
        });

        run("Cancelled order does not match", () -> {
            OrderBook book = new OrderBook("TEST");
            Order sell = new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 100);
            book.addOrder(sell);
            book.cancelOrder(sell.getOrderId());
            List<Trade> trades = book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 100.00, 100));
            assertTrue(trades.isEmpty(), "Cancelled order should not match");
            assertEquals(Order.Status.CANCELLED, sell.getStatus(), "Sell status");
        });

        run("Market order matches against best available price", () -> {
            OrderBook book = new OrderBook("TEST");
            book.addOrder(new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 105.00, 100));
            book.addOrder(new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 103.00, 100));
            List<Trade> trades = book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.MARKET, 0, 100));
            assertEquals(1, trades.size(), "Trade count");
            assertEquals(103.00, trades.get(0).getExecutionPrice(), "Should match cheapest sell (103)");
        });

        run("Cancel non-existent order returns false", () -> {
            OrderBook book = new OrderBook("TEST");
            assertFalse(book.cancelOrder("FAKE-ID"), "Should return false");
        });

        run("Cannot cancel a fully filled order", () -> {
            OrderBook book = new OrderBook("TEST");
            Order sell = new Order("TEST", Order.Side.SELL, Order.Type.LIMIT, 100.00, 100);
            book.addOrder(sell);
            book.addOrder(new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 100.00, 100));
            assertEquals(Order.Status.FILLED, sell.getStatus(), "Sell should be filled");
            try {
                sell.cancel();
                fail("Should have thrown IllegalStateException");
            } catch (IllegalStateException e) { /* expected */ }
        });

        run("O(1) order lookup by ID", () -> {
            OrderBook book = new OrderBook("TEST");
            Order o = new Order("TEST", Order.Side.BUY, Order.Type.LIMIT, 100.00, 50);
            book.addOrder(o);
            Order found = book.getOrder(o.getOrderId());
            assertEquals(o.getOrderId(), found.getOrderId(), "Lookup by ID");
        });
    }

    // ── Concurrency Tests ─────────────────────────────────────────────────────

    static void testConcurrency() throws Exception {
        run("100 concurrent buy+sell pairs produce trades without data corruption", () -> {
            MatchingEngine engine = new MatchingEngine();
            engine.start();
            try {
                int PAIRS   = 100;
                int THREADS = 8;
                ExecutorService pool  = Executors.newFixedThreadPool(THREADS);
                CountDownLatch  latch = new CountDownLatch(PAIRS * 2);

                for (int i = 0; i < PAIRS; i++) {
                    double price = 100.0 + (i % 10);
                    pool.submit(() -> { engine.submitOrder(new Order("AAPL", Order.Side.BUY,  Order.Type.LIMIT, price, 10)); latch.countDown(); });
                    pool.submit(() -> { engine.submitOrder(new Order("AAPL", Order.Side.SELL, Order.Type.LIMIT, price, 10)); latch.countDown(); });
                }
                boolean done = latch.await(5, TimeUnit.SECONDS);
                assertTrue(done, "Timed out");
                Thread.sleep(400);
                pool.shutdown();

                assertTrue(engine.getTotalTrades() > 0, "Expected trades, got 0");
                assertEquals(PAIRS * 2L, engine.getOrdersProcessed(), "All orders processed");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                try { engine.stop(); } catch (InterruptedException ignored) {}
            }
        });

        run("Multi-symbol orders processed in parallel without cross-contamination", () -> {
            MatchingEngine engine = new MatchingEngine();
            engine.start();
            try {
                String[] symbols = {"TSLA", "MSFT", "AMZN", "GOOG"};
                ExecutorService pool = Executors.newFixedThreadPool(4);
                for (String sym : symbols) {
                    pool.submit(() -> {
                        for (int i = 0; i < 25; i++) {
                            engine.submitOrder(new Order(sym, Order.Side.BUY,  Order.Type.LIMIT, 200.0, 5));
                            engine.submitOrder(new Order(sym, Order.Side.SELL, Order.Type.LIMIT, 200.0, 5));
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(5, TimeUnit.SECONDS);
                Thread.sleep(400);
                assertEquals(symbols.length * 25L * 2, engine.getOrdersProcessed(), "All orders processed");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                try { engine.stop(); } catch (InterruptedException ignored) {}
            }
        });

        run("Concurrent cancellations don't corrupt book state", () -> {
            MatchingEngine engine = new MatchingEngine();
            engine.start();
            try {
                // Submit 50 resting orders, then cancel them all concurrently
                AtomicInteger cancelledCount = new AtomicInteger(0);
                String[] orderIds = new String[50];
                for (int i = 0; i < 50; i++) {
                    Order o = new Order("NVDA", Order.Side.BUY, Order.Type.LIMIT, 100.0 + i, 10);
                    engine.submitOrderSync(o);
                    orderIds[i] = o.getOrderId();
                }
                ExecutorService pool = Executors.newFixedThreadPool(8);
                for (String id : orderIds) {
                    pool.submit(() -> {
                        try {
                            Boolean ok = engine.cancelOrder("NVDA", id).get();
                            if (ok) cancelledCount.incrementAndGet();
                        } catch (Exception ignored) {}
                    });
                }
                pool.shutdown();
                pool.awaitTermination(3, TimeUnit.SECONDS);
                assertEquals(50, cancelledCount.get(), "All 50 orders should be cancellable");
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try { engine.stop(); } catch (InterruptedException ignored) {}
            }
        });
    }

    // ── RiskManager Tests ─────────────────────────────────────────────────────

    static void testRiskManager() {
        RiskManager risk = new RiskManager(500_000, 5_000, 0.10);

        run("Valid order passes risk check", () -> {
            Order o = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 150.00, 100);
            assertTrue(risk.check(o).approved, "Should be approved");
        });

        run("Order exceeding max qty is rejected", () -> {
            Order o = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 150.00, 10_000);
            assertFalse(risk.check(o).approved, "Should be rejected: qty too large");
        });

        run("Order exceeding notional limit is rejected", () -> {
            Order o = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 200.00, 4_000); // $800k
            assertFalse(risk.check(o).approved, "Should be rejected: notional too high");
        });

        run("Zero price limit order is rejected", () -> {
            Order o = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 0.00, 100);
            assertFalse(risk.check(o).approved, "Should be rejected: zero price");
        });

        run("Market order passes risk check (no notional check)", () -> {
            Order o = new Order("AAPL", Order.Side.BUY, Order.Type.MARKET, 0, 100);
            assertTrue(risk.check(o).approved, "Market orders skip notional check");
        });
    }
}
