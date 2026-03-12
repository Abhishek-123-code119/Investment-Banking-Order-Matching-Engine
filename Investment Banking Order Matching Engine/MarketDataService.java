package com.jpmc.ome.service;

import com.jpmc.ome.model.Trade;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MarketDataService — listens to the trade stream and maintains real-time
 * market statistics per symbol: last price, VWAP, total volume, trade count.
 *
 * VWAP (Volume-Weighted Average Price) = Σ(price × qty) / Σ(qty)
 * Widely used in finance to evaluate execution quality.
 */
public class MarketDataService {

    private static class SymbolStats {
        double lastPrice   = 0;
        double highPrice   = Double.MIN_VALUE;
        double lowPrice    = Double.MAX_VALUE;
        long   totalVolume = 0;
        double totalNominal = 0;  // Σ(price × qty)
        int    tradeCount  = 0;
        final List<Trade> recentTrades = new ArrayList<>();
    }

    private final ConcurrentHashMap<String, SymbolStats> statsMap = new ConcurrentHashMap<>();

    /** Call this every time a trade executes. */
    public synchronized void onTrade(Trade trade) {
        SymbolStats stats = statsMap.computeIfAbsent(trade.getSymbol(), s -> new SymbolStats());
        stats.lastPrice    = trade.getExecutionPrice();
        stats.highPrice    = Math.max(stats.highPrice, trade.getExecutionPrice());
        stats.lowPrice     = Math.min(stats.lowPrice,  trade.getExecutionPrice());
        stats.totalVolume += trade.getExecutedQuantity();
        stats.totalNominal += trade.getNotionalValue();
        stats.tradeCount++;
        stats.recentTrades.add(trade);
        if (stats.recentTrades.size() > 20) stats.recentTrades.remove(0);
    }

    public synchronized void printStats(String symbol) {
        SymbolStats s = statsMap.get(symbol);
        if (s == null) { System.out.println("No data for " + symbol); return; }
        double vwap = s.totalVolume > 0 ? s.totalNominal / s.totalVolume : 0;
        System.out.printf("""
            ┌─ Market Data: %s ──────────────────┐
            │  Last Price  : $%-10.2f             │
            │  VWAP        : $%-10.2f             │
            │  High / Low  : $%.2f / $%.2f
            │  Total Volume: %-10d              │
            │  Trade Count : %-10d              │
            └─────────────────────────────────────┘
            %n""",
            symbol, s.lastPrice, vwap,
            s.highPrice, s.lowPrice,
            s.totalVolume, s.tradeCount);
    }

    public synchronized void printRecentTrades(String symbol) {
        SymbolStats s = statsMap.get(symbol);
        if (s == null) { System.out.println("No trades for " + symbol); return; }
        System.out.println("\n── Recent Trades: " + symbol + " ──");
        s.recentTrades.forEach(System.out::println);
    }
}
