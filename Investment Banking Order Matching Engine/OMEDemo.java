package com.jpmc.ome.cli;

import com.jpmc.ome.engine.MatchingEngine;
import com.jpmc.ome.model.Order;
import com.jpmc.ome.model.Trade;
import com.jpmc.ome.service.MarketDataService;
import com.jpmc.ome.service.RiskManager;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.*;

/**
 * OMEDemo — interactive demo + stress-test runner.
 *
 * Run Modes:
 *   demo        → Runs a scripted walkthrough showing all features
 *   stress      → Fires 500 concurrent orders across 4 threads
 *   interactive → Manual order entry via stdin
 */
public class OMEDemo {

    private static final MatchingEngine  engine  = new MatchingEngine();
    private static final MarketDataService mds    = new MarketDataService();
    private static final RiskManager risk = new RiskManager(500_000, 5_000, 0.10);

    public static void main(String[] args) throws Exception {
        engine.start();

        System.out.println("""
            ╔══════════════════════════════════════════════════════╗
            ║   JPMC Order Matching Engine  — Java Demo            ║
            ║   DSA: Priority Queues · HashMaps · Concurrency      ║
            ╚══════════════════════════════════════════════════════╝
            """);

        String mode = args.length > 0 ? args[0] : "demo";
        switch (mode) {
            case "stress"      -> runStressTest();
            case "interactive" -> runInteractive();
            default            -> runScriptedDemo();
        }

        engine.stop();
    }

    // ── Scripted demo ─────────────────────────────────────────────────────────

    static void runScriptedDemo() throws Exception {
        System.out.println("━━━ SCENARIO 1: Basic Limit Order Matching ━━━\n");

        // Sell orders
        submit(new Order("AAPL", Order.Side.SELL, Order.Type.LIMIT, 150.00, 100));
        submit(new Order("AAPL", Order.Side.SELL, Order.Type.LIMIT, 149.50, 200));
        submit(new Order("AAPL", Order.Side.SELL, Order.Type.LIMIT, 151.00, 150));

        // Buy orders that don't match yet (price too low)
        submit(new Order("AAPL", Order.Side.BUY,  Order.Type.LIMIT, 148.00, 100));
        submit(new Order("AAPL", Order.Side.BUY,  Order.Type.LIMIT, 147.50, 200));

        System.out.println(engine.getBookDepth("AAPL", 5));

        System.out.println("━━━ SCENARIO 2: Crossing Orders → Trades Execute ━━━\n");

        // This buy at 150.00 matches the sell at 149.50 first (best sell price)
        List<Trade> trades = submitSync(
            new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 150.50, 250));
        System.out.println("\nTrades from crossing order: " + trades.size());
        trades.forEach(t -> { System.out.println(t); mds.onTrade(t); });

        System.out.println(engine.getBookDepth("AAPL", 5));

        System.out.println("━━━ SCENARIO 3: Market Order ━━━\n");

        Order mktOrder = new Order("AAPL", Order.Side.BUY, Order.Type.MARKET, 0, 100);
        List<Trade> mktTrades = submitSync(mktOrder);
        System.out.println("Market order trades: " + mktTrades.size());
        mktTrades.forEach(t -> { System.out.println(t); mds.onTrade(t); });

        System.out.println("━━━ SCENARIO 4: Order Cancellation ━━━\n");

        Order toCancel = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 145.00, 500);
        submitSync(toCancel);
        System.out.println("Submitted: " + toCancel);
        System.out.println("Book before cancel:" + engine.getBookDepth("AAPL", 5));

        engine.cancelOrder("AAPL", toCancel.getOrderId()).get();
        System.out.println("Cancelled order: " + toCancel.getOrderId());
        System.out.println("Book after cancel: " + engine.getBookDepth("AAPL", 5));

        System.out.println("━━━ SCENARIO 5: Risk Manager Rejection ━━━\n");

        Order bigOrder = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 150.00, 10_000);
        RiskManager.RiskResult result = risk.check(bigOrder);
        System.out.println("Risk check on qty=10000: " + result.reason);

        System.out.println("━━━ SCENARIO 6: Multi-Symbol Parallel Matching ━━━\n");

        // TSLA and MSFT orders processed in parallel on separate threads
        submitSync(new Order("TSLA",  Order.Side.SELL, Order.Type.LIMIT, 250.00, 100));
        submitSync(new Order("TSLA",  Order.Side.BUY,  Order.Type.LIMIT, 250.00, 100));
        submitSync(new Order("MSFT",  Order.Side.SELL, Order.Type.LIMIT,  400.00, 200));
        submitSync(new Order("MSFT",  Order.Side.BUY,  Order.Type.LIMIT,  400.00, 200));

        System.out.println("\n── Final Market Data ──");
        mds.printStats("AAPL");

        System.out.println("\n── Engine Stats ──");
        System.out.println("Orders processed : " + engine.getOrdersProcessed());
        System.out.println("Total trades     : " + engine.getTotalTrades());
    }

    // ── Stress test ───────────────────────────────────────────────────────────

    static void runStressTest() throws InterruptedException {
        System.out.println("🔥 STRESS TEST: 500 concurrent orders, 4 producer threads\n");

        int NUM_ORDERS  = 500;
        int NUM_THREADS = 4;
        String[] SYMBOLS = {"AAPL", "TSLA", "MSFT", "AMZN"};

        ExecutorService producers = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch latch = new CountDownLatch(NUM_ORDERS);

        long start = System.currentTimeMillis();

        for (int i = 0; i < NUM_ORDERS; i++) {
            final int idx = i;
            producers.submit(() -> {
                try {
                    String sym = SYMBOLS[idx % SYMBOLS.length];
                    Order.Side side = idx % 2 == 0 ? Order.Side.BUY : Order.Side.SELL;
                    double price = 100.0 + (idx % 20);  // prices cluster → many matches
                    engine.submitOrder(new Order(sym, side, Order.Type.LIMIT, price, 10 + idx % 50));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        Thread.sleep(500); // let queue drain
        long elapsed = System.currentTimeMillis() - start;

        producers.shutdown();
        System.out.printf("✅ %d orders in %dms (%.0f orders/sec)%n",
            NUM_ORDERS, elapsed, NUM_ORDERS / (elapsed / 1000.0));
        System.out.println("Total trades generated: " + engine.getTotalTrades());
        for (String s : SYMBOLS) System.out.print(engine.getBookDepth(s, 3));
    }

    // ── Interactive mode ──────────────────────────────────────────────────────

    static void runInteractive() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("""
            Commands:
              buy  <SYMBOL> <PRICE> <QTY>   — Submit limit buy
              sell <SYMBOL> <PRICE> <QTY>   — Submit limit sell
              mkt  <SYMBOL> buy|sell <QTY>  — Submit market order
              cancel <SYMBOL> <ORDER_ID>    — Cancel order
              book <SYMBOL>                 — Show order book
              stats <SYMBOL>               — Show market stats
              quit                          — Exit
            """);

        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            try {
                switch (parts[0].toLowerCase()) {
                    case "buy" -> {
                        var o = new Order(parts[1], Order.Side.BUY, Order.Type.LIMIT,
                            Double.parseDouble(parts[2]), Integer.parseInt(parts[3]));
                        submitSync(o).forEach(t -> { System.out.println(t); mds.onTrade(t); });
                    }
                    case "sell" -> {
                        var o = new Order(parts[1], Order.Side.SELL, Order.Type.LIMIT,
                            Double.parseDouble(parts[2]), Integer.parseInt(parts[3]));
                        submitSync(o).forEach(t -> { System.out.println(t); mds.onTrade(t); });
                    }
                    case "mkt" -> {
                        Order.Side s = parts[2].equalsIgnoreCase("buy") ? Order.Side.BUY : Order.Side.SELL;
                        var o = new Order(parts[1], s, Order.Type.MARKET, 0, Integer.parseInt(parts[3]));
                        submitSync(o).forEach(t -> { System.out.println(t); mds.onTrade(t); });
                    }
                    case "cancel" -> {
                        boolean ok = engine.cancelOrder(parts[1], parts[2]).get();
                        System.out.println(ok ? "Cancelled." : "Not found / already done.");
                    }
                    case "book"  -> System.out.println(engine.getBookDepth(parts[1], 10));
                    case "stats" -> mds.printStats(parts[1]);
                    case "quit"  -> { return; }
                    default      -> System.out.println("Unknown command.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static void submit(Order o) { engine.submitOrder(o); }

    static List<Trade> submitSync(Order o) throws Exception {
        return engine.submitOrderSync(o);
    }
}
