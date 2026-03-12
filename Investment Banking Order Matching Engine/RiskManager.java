package com.jpmc.ome.service;

import com.jpmc.ome.model.Order;

/**
 * RiskManager — pre-trade risk checks before an order enters the book.
 *
 * In real trading systems, the risk layer enforces:
 *  • Position limits (no trader can buy more than N shares)
 *  • Notional limits (no single order > $X notional)
 *  • Price sanity checks (fat-finger prevention)
 *  • Rate limits (orders per second)
 */
public class RiskManager {

    private final double maxOrderNotional;   // e.g. $1,000,000 per order
    private final int    maxOrderQty;        // e.g. 10,000 shares
    private final double maxPriceDeviation;  // % deviation from "fair value"

    public RiskManager(double maxOrderNotional, int maxOrderQty, double maxPriceDeviation) {
        this.maxOrderNotional  = maxOrderNotional;
        this.maxOrderQty       = maxOrderQty;
        this.maxPriceDeviation = maxPriceDeviation;
    }

    public static class RiskResult {
        public final boolean approved;
        public final String  reason;
        RiskResult(boolean approved, String reason) {
            this.approved = approved;
            this.reason   = reason;
        }
    }

    public RiskResult check(Order order) {
        // 1. Quantity check
        if (order.getQuantity() > maxOrderQty)
            return new RiskResult(false,
                "Order qty " + order.getQuantity() + " exceeds limit " + maxOrderQty);

        // 2. Notional check (skip for MARKET orders with unknown price)
        if (order.getType() == Order.Type.LIMIT) {
            double notional = order.getPrice() * order.getQuantity();
            if (notional > maxOrderNotional)
                return new RiskResult(false,
                    String.format("Notional $%.0f exceeds limit $%.0f", notional, maxOrderNotional));
        }

        // 3. Price sanity — no zero or negative prices on limit orders
        if (order.getType() == Order.Type.LIMIT && order.getPrice() <= 0)
            return new RiskResult(false, "Invalid price: " + order.getPrice());

        return new RiskResult(true, "APPROVED");
    }
}
