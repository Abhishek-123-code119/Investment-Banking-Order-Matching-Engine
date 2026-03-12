package com.jpmc.ome.engine;

import com.jpmc.ome.model.Order;
import com.jpmc.ome.model.Trade;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * OrderBook for a single trading symbol.
 *
 * DSA Highlights:
 * ─────────────────────────────────────────────────────
 * • BUY side  → Max-Heap (PriorityQueue, highest price first)
 * • SELL side → Min-Heap (PriorityQueue, lowest price first)
 * • O(log n)  → insert / remove from heaps
 * • O(1)      → order lookup by ID via HashMap<String, Order>
 * • Order cancellation uses the HashMap for O(1) retrieval,
 *   then lazy-deletion from the heap (checked on pop).
 *
 * Thread Safety:
 * ─────────────────────────────────────────────────────
 * ReentrantReadWriteLock allows concurrent reads of the book
 * depth (market data) while serializing writes (order submission
 * and matching). This mirrors real exchange design where read
 * throughput for market-data feeds is much higher than write.
 */
public class OrderBook {

    private final String symbol;

    // Max-Heap for buy orders: highest price wins; ties broken by sequence (FIFO)
    private final PriorityQueue<Order> buyOrders = new PriorityQueue<>((a, b) -> {
        int cmp = Double.compare(b.getPrice(), a.getPrice()); // descending price
        if (cmp != 0) return cmp;
        return Long.compare(a.getSequenceNumber(), b.getSequenceNumber()); // FIFO
    });

    // Min-Heap for sell orders: lowest price wins; ties broken by sequence (FIFO)
    private final PriorityQueue<Order> sellOrders = new PriorityQueue<>((a, b) -> {
        int cmp = Double.compare(a.getPrice(), b.getPrice()); // ascending price
        if (cmp != 0) return cmp;
        return Long.compare(a.getSequenceNumber(), b.getSequenceNumber()); // FIFO
    });

    // O(1) order lookup — used for cancellations and status queries
    private final Map<String, Order> orderIndex = new HashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Add an order and attempt to match it immediately.
     * Returns the list of trades generated.
     */
    public List<Trade> addOrder(Order order) {
        writeLock.lock();
        try {
            orderIndex.put(order.getOrderId(), order);
            if (order.getSide() == Order.Side.BUY) {
                buyOrders.offer(order);
            } else {
                sellOrders.offer(order);
            }
            return match();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Cancel an order by ID — O(1) lookup, O(log n) lazy removal from heap.
     */
    public boolean cancelOrder(String orderId) {
        writeLock.lock();
        try {
            Order order = orderIndex.get(orderId);
            if (order == null || !order.isActive()) return false;
            order.cancel();
            // Heap cleanup happens lazily in match() and peek operations
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Core matching algorithm — price-time priority.
     * Runs inside writeLock; processes as many matches as possible.
     */
    private List<Trade> match() {
        List<Trade> trades = new ArrayList<>();

        while (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {

            // Drain cancelled / filled orders from heap tops (lazy deletion)
            while (!buyOrders.isEmpty()  && !buyOrders.peek().isActive())  buyOrders.poll();
            while (!sellOrders.isEmpty() && !sellOrders.peek().isActive()) sellOrders.poll();

            if (buyOrders.isEmpty() || sellOrders.isEmpty()) break;

            Order bestBuy  = buyOrders.peek();
            Order bestSell = sellOrders.peek();

            // For MARKET orders, price check is skipped
            boolean priceMatches =
                bestBuy.getType()  == Order.Type.MARKET ||
                bestSell.getType() == Order.Type.MARKET ||
                bestBuy.getPrice() >= bestSell.getPrice();

            if (!priceMatches) break;

            // Execution price: resting order's price (maker-taker model)
            double execPrice = bestSell.getType() == Order.Type.MARKET
                ? bestBuy.getPrice()
                : bestSell.getPrice();

            int execQty = Math.min(bestBuy.getRemainingQty(), bestSell.getRemainingQty());

            bestBuy.fill(execQty);
            bestSell.fill(execQty);

            Trade trade = new Trade(symbol,
                bestBuy.getOrderId(), bestSell.getOrderId(),
                execPrice, execQty);
            trades.add(trade);

            // Remove fully filled orders from heaps
            if (!bestBuy.isActive())  buyOrders.poll();
            if (!bestSell.isActive()) sellOrders.poll();
        }

        return trades;
    }

    /** Query an order by ID — O(1). */
    public Order getOrder(String orderId) {
        readLock.lock();
        try {
            return orderIndex.get(orderId);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns a snapshot of the order book depth (top N levels).
     * Uses readLock so market-data consumers don't block order flow.
     */
    public String getBookDepth(int levels) {
        readLock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("\n═══════════ ORDER BOOK: %s ═══════════\n", symbol));

            // Collect active sell orders, sort descending (display high → low)
            List<Order> sells = sellOrders.stream()
                .filter(Order::isActive)
                .sorted(Comparator.comparingDouble(Order::getPrice).reversed())
                .limit(levels)
                .toList();

            List<Order> buys = buyOrders.stream()
                .filter(Order::isActive)
                .sorted(Comparator.comparingDouble(Order::getPrice).reversed())
                .limit(levels)
                .toList();

            sb.append(String.format("  %-8s  %-8s  %-8s\n", "PRICE", "QTY", "SIDE"));
            sb.append("  ─────────────────────────────\n");
            for (Order s : sells) {
                sb.append(String.format("  %-8.2f  %-8d  🔴 SELL\n", s.getPrice(), s.getRemainingQty()));
            }
            sb.append("  ─── SPREAD ──────────────────\n");
            for (Order b : buys) {
                sb.append(String.format("  %-8.2f  %-8d  🟢 BUY\n",  b.getPrice(), b.getRemainingQty()));
            }
            sb.append("════════════════════════════════\n");
            return sb.toString();
        } finally {
            readLock.unlock();
        }
    }

    public String getSymbol() { return symbol; }
}
