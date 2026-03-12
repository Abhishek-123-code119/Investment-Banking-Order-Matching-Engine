package com.jpmc.ome.model;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a trading order in the system.
 * Immutable core fields; status is mutable via volatile for thread-safety.
 */
public class Order {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    public enum Side { BUY, SELL }
    public enum Type { LIMIT, MARKET }
    public enum Status { PENDING, PARTIALLY_FILLED, FILLED, CANCELLED }

    private final String orderId;
    private final String symbol;
    private final Side side;
    private final Type type;
    private final double price;          // limit price (0 for MARKET)
    private final int quantity;          // original quantity
    private volatile int remainingQty;
    private volatile Status status;
    private final Instant timestamp;
    private final long sequenceNumber;   // tiebreaker for same-price orders (price-time priority)

    public Order(String symbol, Side side, Type type, double price, int quantity) {
        this.orderId       = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.symbol        = symbol;
        this.side          = side;
        this.type          = type;
        this.price         = price;
        this.quantity      = quantity;
        this.remainingQty  = quantity;
        this.status        = Status.PENDING;
        this.timestamp     = Instant.now();
        this.sequenceNumber = SEQUENCE.incrementAndGet();
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String  getOrderId()       { return orderId; }
    public String  getSymbol()        { return symbol; }
    public Side    getSide()          { return side; }
    public Type    getType()          { return type; }
    public double  getPrice()         { return price; }
    public int     getQuantity()      { return quantity; }
    public int     getRemainingQty()  { return remainingQty; }
    public Status  getStatus()        { return status; }
    public Instant getTimestamp()     { return timestamp; }
    public long    getSequenceNumber(){ return sequenceNumber; }

    // ── Mutators (thread-safe enough; engine holds lock when calling these) ──

    public synchronized void fill(int filledQty) {
        if (filledQty <= 0 || filledQty > remainingQty)
            throw new IllegalArgumentException("Invalid fill qty: " + filledQty);
        remainingQty -= filledQty;
        status = (remainingQty == 0) ? Status.FILLED : Status.PARTIALLY_FILLED;
    }

    public synchronized void cancel() {
        if (status == Status.FILLED)
            throw new IllegalStateException("Cannot cancel a fully filled order.");
        status = Status.CANCELLED;
    }

    public boolean isActive() {
        return status == Status.PENDING || status == Status.PARTIALLY_FILLED;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s %s %s @ %.2f | qty=%d rem=%d | %s",
            orderId, side, type, symbol, price, quantity, remainingQty, status);
    }
}
