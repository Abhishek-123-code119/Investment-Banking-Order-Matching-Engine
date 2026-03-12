package com.jpmc.ome.engine;

import com.jpmc.ome.model.Order;
import com.jpmc.ome.model.Trade;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MatchingEngine — multi-symbol, multi-threaded order matching.
 *
 * Concurrency Design:
 * ─────────────────────────────────────────────────────────────
 * • One dedicated single-threaded ExecutorService per symbol.
 *   This ensures all mutations to a given OrderBook are
 *   serialized on one thread → no cross-order race conditions.
 *
 * • Incoming orders are submitted via submit(Order) which puts
 *   them into a BlockingQueue. A dispatcher thread routes each
 *   order to the correct symbol executor.
 *
 * • A shared CopyOnWriteArrayList stores all executed trades
 *   for audit/reporting (lock-free reads, occasional writes).
 *
 * Why not a single global lock?
 *   A single lock would serialize orders across ALL symbols.
 *   Real exchanges process symbols independently — AAPL matching
 *   should never wait for TSLA matching. Per-symbol executors
 *   achieve this parallelism.
 */
public class MatchingEngine {

    // symbol → its OrderBook
    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();

    // symbol → single-threaded executor (serializes mutations per book)
    private final ConcurrentHashMap<String, ExecutorService> symbolExecutors = new ConcurrentHashMap<>();

    // Global trade log (thread-safe; reads far outnumber writes)
    private final List<Trade> tradeLog = new CopyOnWriteArrayList<>();

    // Incoming order queue — decouples producers from the engine
    private final BlockingQueue<Order> incomingQueue = new LinkedBlockingQueue<>();

    // Dispatcher thread
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "order-dispatcher"));

    private volatile boolean running = false;

    private final AtomicLong ordersProcessed = new AtomicLong(0);
    private final AtomicLong totalTrades     = new AtomicLong(0);

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() {
        running = true;
        dispatcher.submit(this::dispatchLoop);
        System.out.println("🚀 MatchingEngine started.");
    }

    public void stop() throws InterruptedException {
        running = false;
        dispatcher.shutdownNow();
        symbolExecutors.values().forEach(ExecutorService::shutdown);
        symbolExecutors.values().forEach(ex -> {
            try { ex.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        System.out.println("🛑 MatchingEngine stopped. Processed "
            + ordersProcessed.get() + " orders, generated "
            + totalTrades.get() + " trades.");
    }

    // ── Order submission ─────────────────────────────────────────────────────

    /**
     * Non-blocking order submission. Adds to queue; dispatcher picks it up.
     */
    public void submitOrder(Order order) {
        ensureBookExists(order.getSymbol());
        incomingQueue.offer(order);
    }

    /**
     * Synchronous submission — waits for the order to be fully processed
     * and returns any resulting trades. Useful for testing.
     */
    public List<Trade> submitOrderSync(Order order) throws InterruptedException, ExecutionException {
        ensureBookExists(order.getSymbol());
        ExecutorService exec = symbolExecutors.get(order.getSymbol());
        Future<List<Trade>> future = exec.submit(() -> {
            List<Trade> trades = books.get(order.getSymbol()).addOrder(order);
            tradeLog.addAll(trades);
            totalTrades.addAndGet(trades.size());
            ordersProcessed.incrementAndGet();
            return trades;
        });
        return future.get();
    }

    /**
     * Cancel an order by ID within a given symbol's book.
     */
    public Future<Boolean> cancelOrder(String symbol, String orderId) {
        ensureBookExists(symbol);
        return symbolExecutors.get(symbol).submit(
            () -> books.get(symbol).cancelOrder(orderId)
        );
    }

    // ── Dispatcher loop ───────────────────────────────────────────────────────

    private void dispatchLoop() {
        while (running || !incomingQueue.isEmpty()) {
            try {
                Order order = incomingQueue.poll(100, TimeUnit.MILLISECONDS);
                if (order == null) continue;

                String symbol = order.getSymbol();
                symbolExecutors.get(symbol).submit(() -> {
                    List<Trade> trades = books.get(symbol).addOrder(order);
                    if (!trades.isEmpty()) {
                        tradeLog.addAll(trades);
                        totalTrades.addAndGet(trades.size());
                        trades.forEach(t -> System.out.println("  " + t));
                    }
                    ordersProcessed.incrementAndGet();
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void ensureBookExists(String symbol) {
        books.computeIfAbsent(symbol, OrderBook::new);
        symbolExecutors.computeIfAbsent(symbol,
            s -> Executors.newSingleThreadExecutor(
                r -> new Thread(r, "book-" + s)));
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Order getOrder(String symbol, String orderId) {
        OrderBook book = books.get(symbol);
        return book == null ? null : book.getOrder(orderId);
    }

    public String getBookDepth(String symbol, int levels) {
        OrderBook book = books.get(symbol);
        return book == null ? "No book for symbol: " + symbol : book.getBookDepth(levels);
    }

    public List<Trade> getTradeLog() { return Collections.unmodifiableList(tradeLog); }

    public long getOrdersProcessed() { return ordersProcessed.get(); }
    public long getTotalTrades()     { return totalTrades.get(); }
}
