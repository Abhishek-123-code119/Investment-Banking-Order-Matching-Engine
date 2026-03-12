package com.jpmc.ome.model;

import java.time.Instant;

/**
 * Represents an executed trade — the result of matching a buy and sell order.
 */
public class Trade {

    private final String tradeId;
    private final String symbol;
    private final String buyOrderId;
    private final String sellOrderId;
    private final double executionPrice;
    private final int    executedQuantity;
    private final Instant executedAt;

    private static final java.util.concurrent.atomic.AtomicLong TRADE_SEQ =
        new java.util.concurrent.atomic.AtomicLong(1);

    public Trade(String symbol, String buyOrderId, String sellOrderId,
                 double executionPrice, int executedQuantity) {
        this.tradeId          = "TRD-" + String.format("%06d", TRADE_SEQ.getAndIncrement());
        this.symbol           = symbol;
        this.buyOrderId       = buyOrderId;
        this.sellOrderId      = sellOrderId;
        this.executionPrice   = executionPrice;
        this.executedQuantity = executedQuantity;
        this.executedAt       = Instant.now();
    }

    public String  getTradeId()          { return tradeId; }
    public String  getSymbol()           { return symbol; }
    public String  getBuyOrderId()       { return buyOrderId; }
    public String  getSellOrderId()      { return sellOrderId; }
    public double  getExecutionPrice()   { return executionPrice; }
    public int     getExecutedQuantity() { return executedQuantity; }
    public Instant getExecutedAt()       { return executedAt; }
    public double  getNotionalValue()    { return executionPrice * executedQuantity; }

    @Override
    public String toString() {
        return String.format("✅ %s | %s | BUY[%s] x SELL[%s] | %d @ $%.2f | Notional=$%.2f",
            tradeId, symbol, buyOrderId, sellOrderId,
            executedQuantity, executionPrice, getNotionalValue());
    }
}
