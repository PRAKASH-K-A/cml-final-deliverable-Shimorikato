package com.helesto.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.TradeEntity;

/**
 * G2-M4: Trade Service
 * - Trade persistence and retrieval
 * - Trade event publishing
 * - Trade statistics
 */
@ApplicationScoped
public class TradeService {

    private static final Logger LOG = LoggerFactory.getLogger(TradeService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    @Inject
    EntityManager entityManager;
    
    // Trade listeners for real-time updates
    private final List<Consumer<TradeEntity>> tradeListeners = new CopyOnWriteArrayList<>();
    
    /**
     * Create a trade from match result
     */
    @Transactional
    public TradeEntity createTrade(MatchingEngine.Fill fill, 
                                   String incomingOrderId, String incomingClOrdId, 
                                   String incomingClientId, String incomingSide,
                                   String symbol) {
        TradeEntity trade = new TradeEntity();
        trade.setTradeId(fill.tradeId);
        trade.setExecId(fill.execId);
        trade.setSymbol(symbol);
        trade.setPrice(fill.price);
        trade.setQuantity(fill.quantity);
        
        // Set buy/sell sides based on incoming order side
        if ("BUY".equals(incomingSide) || "1".equals(incomingSide)) {
            trade.setBuyOrderId(incomingOrderId);
            trade.setBuyClOrdId(incomingClOrdId);
            trade.setBuyClientId(incomingClientId);
            trade.setSellOrderId(fill.contraOrderId);
            trade.setSellClientId(fill.contraClientId);
            trade.setAggressorSide("BUY");
        } else {
            trade.setSellOrderId(incomingOrderId);
            trade.setSellClOrdId(incomingClOrdId);
            trade.setSellClientId(incomingClientId);
            trade.setBuyOrderId(fill.contraOrderId);
            trade.setBuyClientId(fill.contraClientId);
            trade.setAggressorSide("SELL");
        }
        
        trade.setTradeDate(LocalDate.now().format(DATE_FORMAT));
        trade.setSettlementDate(LocalDate.now().plusDays(2).format(DATE_FORMAT)); // T+2
        trade.setTradeStatus("CONFIRMED");
        trade.setTradeType("REGULAR");
        
        entityManager.persist(trade);
        LOG.info("Created trade: {} {} {} @ {}", trade.getTradeId(), symbol, fill.quantity, fill.price);
        
        // Notify listeners
        notifyTradeListeners(trade);
        
        return trade;
    }
    
    /**
     * Get trade by ID
     */
    public TradeEntity getTradeById(String tradeId) {
        return entityManager.createQuery(
            "SELECT t FROM TradeEntity t WHERE t.tradeId = :tradeId", TradeEntity.class)
            .setParameter("tradeId", tradeId)
            .getResultStream()
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Get trades by symbol
     */
    public List<TradeEntity> getTradesBySymbol(String symbol) {
        return entityManager.createQuery(
            "SELECT t FROM TradeEntity t WHERE t.symbol = :symbol ORDER BY t.createdAt DESC", 
            TradeEntity.class)
            .setParameter("symbol", symbol)
            .getResultList();
    }
    
    /**
     * Get trades by client
     */
    public List<TradeEntity> getTradesByClient(String clientId) {
        return entityManager.createQuery(
            "SELECT t FROM TradeEntity t WHERE t.buyClientId = :clientId OR t.sellClientId = :clientId " +
            "ORDER BY t.createdAt DESC", TradeEntity.class)
            .setParameter("clientId", clientId)
            .getResultList();
    }
    
    /**
     * Get recent trades
     */
    public List<TradeEntity> getRecentTrades(int limit) {
        return entityManager.createQuery(
            "SELECT t FROM TradeEntity t ORDER BY t.createdAt DESC", TradeEntity.class)
            .setMaxResults(limit)
            .getResultList();
    }
    
    /**
     * Get trades for a date range
     */
    public List<TradeEntity> getTradesByDateRange(String startDate, String endDate) {
        return entityManager.createQuery(
            "SELECT t FROM TradeEntity t WHERE t.tradeDate >= :startDate AND t.tradeDate <= :endDate " +
            "ORDER BY t.createdAt DESC", TradeEntity.class)
            .setParameter("startDate", startDate)
            .setParameter("endDate", endDate)
            .getResultList();
    }
    
    /**
     * Get trade statistics for a symbol
     */
    public TradeStats getTradeStats(String symbol) {
        List<Object[]> result = entityManager.createQuery(
            "SELECT COUNT(t), SUM(t.quantity), AVG(t.price), MIN(t.price), MAX(t.price) " +
            "FROM TradeEntity t WHERE t.symbol = :symbol AND t.tradeDate = :today", Object[].class)
            .setParameter("symbol", symbol)
            .setParameter("today", LocalDate.now().format(DATE_FORMAT))
            .getResultList();
        
        TradeStats stats = new TradeStats();
        stats.symbol = symbol;
        
        if (!result.isEmpty() && result.get(0) != null) {
            Object[] row = result.get(0);
            stats.tradeCount = row[0] != null ? ((Number) row[0]).longValue() : 0;
            stats.totalVolume = row[1] != null ? ((Number) row[1]).longValue() : 0;
            stats.avgPrice = row[2] != null ? ((Number) row[2]).doubleValue() : 0;
            stats.lowPrice = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
            stats.highPrice = row[4] != null ? ((Number) row[4]).doubleValue() : 0;
        }
        
        return stats;
    }
    
    /**
     * Cancel/bust a trade
     */
    @Transactional
    public boolean bustTrade(String tradeId, String reason) {
        TradeEntity trade = getTradeById(tradeId);
        if (trade == null) {
            LOG.warn("Trade not found for bust: {}", tradeId);
            return false;
        }
        
        trade.setTradeStatus("BUSTED");
        entityManager.merge(trade);
        LOG.info("Trade busted: {} - {}", tradeId, reason);
        
        return true;
    }
    
    /**
     * Register trade listener for real-time updates
     */
    public void addTradeListener(Consumer<TradeEntity> listener) {
        tradeListeners.add(listener);
    }
    
    /**
     * Remove trade listener
     */
    public void removeTradeListener(Consumer<TradeEntity> listener) {
        tradeListeners.remove(listener);
    }
    
    private void notifyTradeListeners(TradeEntity trade) {
        for (Consumer<TradeEntity> listener : tradeListeners) {
            try {
                listener.accept(trade);
            } catch (Exception e) {
                LOG.error("Error notifying trade listener", e);
            }
        }
    }
    
    // ================== Inner Classes ==================
    
    public static class TradeStats {
        public String symbol;
        public long tradeCount;
        public long totalVolume;
        public double avgPrice;
        public double lowPrice;
        public double highPrice;
        public double vwap;
    }
}
