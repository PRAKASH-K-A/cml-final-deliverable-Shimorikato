package com.helesto.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.TradeEntity;
import com.helesto.socket.WebSocketAggregator;

/**
 * G3-M4: Trade-Driven Pricing Updates
 * - Consumes TradeExecuted events and recomputes option fair prices dynamically
 * - Throttles/aggregates updates per symbol per 100ms to reduce UI churn
 * - Publishes OptionPriceUpdated events on WebSocket stream
 */
@ApplicationScoped
public class TradeDrivenPricingService {

    private static final Logger LOG = LoggerFactory.getLogger(TradeDrivenPricingService.class);
    
    // Throttling configuration
    private static final long THROTTLE_INTERVAL_MS = 100; // 100ms aggregation window
    private static final int MAX_PENDING_UPDATES = 1000;
    
    @Inject
    BlackScholesPricingService pricingService;
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    TradeService tradeService;
    
    @Inject
    WebSocketAggregator webSocketAggregator;
    
    @Inject
    MarketDataPoller marketDataPoller;
    
    // Latest trade prices by symbol
    private final ConcurrentMap<String, TradePrice> latestPrices = new ConcurrentHashMap<>();
    
    // Pending price updates (throttled)
    private final ConcurrentMap<String, OptionPriceUpdate> pendingUpdates = new ConcurrentHashMap<>();
    
    // Option chain configurations per underlying
    private final ConcurrentMap<String, OptionChainConfig> optionChains = new ConcurrentHashMap<>();
    
    // Price update listeners
    private final List<Consumer<OptionPriceUpdate>> priceListeners = new CopyOnWriteArrayList<>();
    
    // Scheduler for throttled broadcasting
    private ScheduledExecutorService scheduler;
    
    // Telemetry
    private final PricingTelemetry telemetry = new PricingTelemetry();
    
    @PostConstruct
    void init() {
        LOG.info("Initializing Trade-Driven Pricing Service...");
        
        // Initialize default option chains for major underlyings
        initializeOptionChains();
        
        // Register as trade listener
        tradeService.addTradeListener(this::onTradeExecuted);
        
        // Start throttled broadcast scheduler
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pricing-broadcaster");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(this::broadcastPendingUpdates, 
            THROTTLE_INTERVAL_MS, THROTTLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        LOG.info("Trade-Driven Pricing Service initialized");
    }
    
    @PreDestroy
    void cleanup() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
    
    private void initializeOptionChains() {
        // Configure option chains for major symbols
        String[] majorSymbols = {"AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA"};
        
        for (String symbol : majorSymbols) {
            OptionChainConfig config = new OptionChainConfig();
            config.underlying = symbol;
            config.strikes = generateStrikes(symbol);
            config.expirations = generateExpirations();
            config.riskFreeRate = 0.05; // 5%
            config.baseVolatility = 0.25; // 25%
            optionChains.put(symbol, config);
        }
        
        LOG.info("Initialized option chains for {} underlyings", majorSymbols.length);
    }
    
    private double[] generateStrikes(String symbol) {
        // Get current price from reference data
        ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
        double basePrice = md != null ? md.lastPrice : 100.0;
        
        // Generate strikes around current price (-20% to +20%)
        double[] strikes = new double[9];
        for (int i = 0; i < 9; i++) {
            double offset = (i - 4) * 0.05; // -20%, -15%, -10%, -5%, ATM, +5%, +10%, +15%, +20%
            strikes[i] = Math.round(basePrice * (1 + offset) * 100) / 100.0;
        }
        return strikes;
    }
    
    private double[] generateExpirations() {
        // Generate expirations: 7d, 14d, 30d, 60d, 90d
        return new double[] {7/365.0, 14/365.0, 30/365.0, 60/365.0, 90/365.0};
    }
    
    /**
     * Trade event handler - called when a trade executes
     */
    public void onTradeExecuted(TradeEntity trade) {
        long startTime = System.nanoTime();
        
        try {
            String symbol = trade.getSymbol();
            double tradePrice = trade.getPrice();
            long tradeQty = trade.getQuantity();
            
            // Update latest price for symbol
            TradePrice current = latestPrices.compute(symbol, (k, v) -> {
                if (v == null) {
                    v = new TradePrice();
                    v.symbol = symbol;
                }
                // VWAP-style update
                v.totalVolume += tradeQty;
                v.volumeWeightedSum += tradePrice * tradeQty;
                v.lastPrice = tradePrice;
                v.lastUpdateTime = System.currentTimeMillis();
                v.tradeCount++;
                return v;
            });
            
            // Update reference data with latest price
            referenceDataService.updateLastPrice(symbol, tradePrice);
            
            // Update market data poller (triggers WebSocket broadcast)
            marketDataPoller.updatePriceFromTrade(symbol, tradePrice, tradeQty);
            
            // Recompute option prices if symbol has options
            if (optionChains.containsKey(symbol)) {
                recomputeOptionPrices(symbol, tradePrice);
            }
            
            telemetry.tradesProcessed.incrementAndGet();
            telemetry.totalProcessingTimeNanos.addAndGet(System.nanoTime() - startTime);
            
        } catch (Exception e) {
            LOG.error("Error processing trade for pricing: {}", e.getMessage());
            telemetry.processingErrors.incrementAndGet();
        }
    }
    
    /**
     * Recompute all option prices for a symbol
     */
    private void recomputeOptionPrices(String symbol, double underlyingPrice) {
        OptionChainConfig config = optionChains.get(symbol);
        if (config == null) return;
        
        // Recalculate strikes based on new price
        config.strikes = generateStrikes(symbol);
        
        List<OptionPrice> updatedPrices = new ArrayList<>();
        
        for (double strike : config.strikes) {
            for (double expiry : config.expirations) {
                // Calculate implied volatility from market conditions
                double vol = estimateVolatility(symbol, strike, underlyingPrice, config.baseVolatility);
                
                // Price call
                double callPrice = pricingService.callPrice(
                    underlyingPrice, strike, expiry, config.riskFreeRate, vol);
                BlackScholesPricingService.Greeks callGreeks = pricingService.callGreeks(
                    underlyingPrice, strike, expiry, config.riskFreeRate, vol);
                
                OptionPrice call = new OptionPrice();
                call.underlying = symbol;
                call.optionType = "CALL";
                call.strike = strike;
                call.expiry = expiry;
                call.price = callPrice;
                call.delta = callGreeks.delta;
                call.gamma = callGreeks.gamma;
                call.theta = callGreeks.theta;
                call.vega = callGreeks.vega;
                call.impliedVol = vol;
                call.underlyingPrice = underlyingPrice;
                updatedPrices.add(call);
                
                // Price put
                double putPrice = pricingService.putPrice(
                    underlyingPrice, strike, expiry, config.riskFreeRate, vol);
                BlackScholesPricingService.Greeks putGreeks = pricingService.putGreeks(
                    underlyingPrice, strike, expiry, config.riskFreeRate, vol);
                
                OptionPrice put = new OptionPrice();
                put.underlying = symbol;
                put.optionType = "PUT";
                put.strike = strike;
                put.expiry = expiry;
                put.price = putPrice;
                put.delta = putGreeks.delta;
                put.gamma = putGreeks.gamma;
                put.theta = putGreeks.theta;
                put.vega = putGreeks.vega;
                put.impliedVol = vol;
                put.underlyingPrice = underlyingPrice;
                updatedPrices.add(put);
            }
        }
        
        // Queue update for throttled broadcast
        OptionPriceUpdate update = new OptionPriceUpdate();
        update.symbol = symbol;
        update.underlyingPrice = underlyingPrice;
        update.prices = updatedPrices;
        update.timestamp = System.currentTimeMillis();
        
        pendingUpdates.put(symbol, update);
        telemetry.priceUpdatesGenerated.incrementAndGet();
    }
    
    /**
     * Estimate volatility based on price movement and moneyness
     */
    private double estimateVolatility(String symbol, double strike, double spot, double baseVol) {
        // Volatility smile approximation
        double moneyness = strike / spot;
        double skew = 0;
        
        if (moneyness < 0.95) {
            // OTM puts - higher vol
            skew = (0.95 - moneyness) * 0.5;
        } else if (moneyness > 1.05) {
            // OTM calls - slightly higher vol
            skew = (moneyness - 1.05) * 0.3;
        }
        
        return baseVol + skew;
    }
    
    /**
     * Broadcast pending updates (called by scheduler)
     */
    private void broadcastPendingUpdates() {
        if (pendingUpdates.isEmpty()) return;
        
        // Get and clear pending updates
        Map<String, OptionPriceUpdate> updates = new HashMap<>(pendingUpdates);
        pendingUpdates.clear();
        
        for (OptionPriceUpdate update : updates.values()) {
            try {
                // Broadcast to WebSocket
                broadcastOptionPriceUpdate(update);
                
                // Notify listeners
                for (Consumer<OptionPriceUpdate> listener : priceListeners) {
                    listener.accept(update);
                }
                
                telemetry.updatesBroadcast.incrementAndGet();
            } catch (Exception e) {
                LOG.error("Error broadcasting option price update: {}", e.getMessage());
            }
        }
    }
    
    private void broadcastOptionPriceUpdate(OptionPriceUpdate update) {
        // Create message for WebSocket
        Map<String, Object> message = new HashMap<>();
        message.put("symbol", update.symbol);
        message.put("underlyingPrice", update.underlyingPrice);
        message.put("timestamp", update.timestamp);
        
        // Summarize option chain
        List<Map<String, Object>> summary = new ArrayList<>();
        for (OptionPrice op : update.prices) {
            Map<String, Object> option = new HashMap<>();
            option.put("type", op.optionType);
            option.put("strike", op.strike);
            option.put("expiry", String.format("%.0fd", op.expiry * 365));
            option.put("price", Math.round(op.price * 100) / 100.0);
            option.put("delta", Math.round(op.delta * 1000) / 1000.0);
            option.put("iv", Math.round(op.impliedVol * 10000) / 100.0); // As percentage
            summary.add(option);
        }
        message.put("options", summary);
        
        // Broadcast via WebSocket aggregator
        try {
            webSocketAggregator.broadcastOptionPriceUpdate(update.symbol, message);
        } catch (Exception e) {
            // WebSocket broadcast method might not exist yet
            LOG.debug("WebSocket broadcast not available: {}", e.getMessage());
        }
    }
    
    /**
     * Add price update listener
     */
    public void addPriceListener(Consumer<OptionPriceUpdate> listener) {
        priceListeners.add(listener);
    }
    
    /**
     * Remove price update listener
     */
    public void removePriceListener(Consumer<OptionPriceUpdate> listener) {
        priceListeners.remove(listener);
    }
    
    /**
     * Get latest trade price for symbol
     */
    public TradePrice getLatestPrice(String symbol) {
        return latestPrices.get(symbol);
    }
    
    /**
     * Get VWAP for symbol
     */
    public double getVwap(String symbol) {
        TradePrice tp = latestPrices.get(symbol);
        if (tp == null || tp.totalVolume == 0) return 0;
        return tp.volumeWeightedSum / tp.totalVolume;
    }
    
    /**
     * Get option chain for symbol
     */
    public OptionPriceUpdate getOptionChain(String symbol) {
        return pendingUpdates.get(symbol);
    }
    
    /**
     * Get current option prices for a symbol (computed on demand)
     */
    public OptionPriceUpdate computeOptionChain(String symbol) {
        ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
        if (md == null) return null;
        
        recomputeOptionPrices(symbol, md.lastPrice);
        return pendingUpdates.get(symbol);
    }
    
    /**
     * Get telemetry data
     */
    public PricingTelemetry getTelemetry() {
        return telemetry;
    }
    
    // ================== Inner Classes ==================
    
    public static class TradePrice {
        public String symbol;
        public double lastPrice;
        public double volumeWeightedSum;
        public long totalVolume;
        public long tradeCount;
        public long lastUpdateTime;
        
        public double getVwap() {
            return totalVolume > 0 ? volumeWeightedSum / totalVolume : lastPrice;
        }
    }
    
    public static class OptionPrice {
        public String underlying;
        public String optionType; // CALL or PUT
        public double strike;
        public double expiry; // In years
        public double price;
        public double delta;
        public double gamma;
        public double theta;
        public double vega;
        public double impliedVol;
        public double underlyingPrice;
    }
    
    public static class OptionPriceUpdate {
        public String symbol;
        public double underlyingPrice;
        public List<OptionPrice> prices;
        public long timestamp;
    }
    
    private static class OptionChainConfig {
        String underlying;
        double[] strikes;
        double[] expirations;
        double riskFreeRate;
        double baseVolatility;
    }
    
    public static class PricingTelemetry {
        public final java.util.concurrent.atomic.AtomicLong tradesProcessed = 
            new java.util.concurrent.atomic.AtomicLong(0);
        public final java.util.concurrent.atomic.AtomicLong priceUpdatesGenerated = 
            new java.util.concurrent.atomic.AtomicLong(0);
        public final java.util.concurrent.atomic.AtomicLong updatesBroadcast = 
            new java.util.concurrent.atomic.AtomicLong(0);
        public final java.util.concurrent.atomic.AtomicLong processingErrors = 
            new java.util.concurrent.atomic.AtomicLong(0);
        public final java.util.concurrent.atomic.AtomicLong totalProcessingTimeNanos = 
            new java.util.concurrent.atomic.AtomicLong(0);
        
        public double getAvgProcessingTimeMs() {
            long count = tradesProcessed.get();
            return count > 0 ? (totalProcessingTimeNanos.get() / count) / 1_000_000.0 : 0;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("tradesProcessed", tradesProcessed.get());
            map.put("priceUpdatesGenerated", priceUpdatesGenerated.get());
            map.put("updatesBroadcast", updatesBroadcast.get());
            map.put("processingErrors", processingErrors.get());
            map.put("avgProcessingTimeMs", String.format("%.3f", getAvgProcessingTimeMs()));
            return map;
        }
    }
}
