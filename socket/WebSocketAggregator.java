package com.helesto.socket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helesto.model.OrderEntity;
import com.helesto.model.TradeEntity;
import com.helesto.service.OrderBookManager;
import com.helesto.service.ReferenceDataService;
import com.helesto.service.TelemetryService;
import com.helesto.service.TradeService;

/**
 * G4-M1: WebSocket Aggregator Gateway
 * - Single WS endpoint aggregating order states, trades, market data, positions
 * - Client subscription management
 * - Efficient broadcast with batching
 */
@ServerEndpoint("/ws/aggregator")
@ApplicationScoped
public class WebSocketAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketAggregator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    // Connected sessions
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    
    // Subscriptions per session
    private final Map<Session, ClientSubscription> subscriptions = new ConcurrentHashMap<>();
    
    // Message batching
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Session, List<Message>> pendingMessages = new ConcurrentHashMap<>();
    private static final long BATCH_INTERVAL_MS = 50; // 50ms batching
    
    // G2-M5: Trade replay configuration
    private static final int TRADE_REPLAY_COUNT = 50; // Number of trades to replay on reconnect
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    TradeService tradeService;
    
    @Inject
    TelemetryService telemetryService;
    
    public WebSocketAggregator() {
        // Start batch sender
        scheduler.scheduleAtFixedRate(this::flushPendingMessages, 
                BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        subscriptions.put(session, new ClientSubscription());
        pendingMessages.put(session, new CopyOnWriteArrayList<>());
        LOG.info("WebSocket client connected: {}", session.getId());
        
        // Record telemetry
        if (telemetryService != null) {
            telemetryService.recordWsConnection(true);
        }
        
        // Send welcome message with replay capability notification
        sendDirect(session, new Message("CONNECTED", Map.of(
            "sessionId", session.getId(),
            "tradeReplayAvailable", true,
            "tradeReplayCount", TRADE_REPLAY_COUNT,
            "timestamp", System.currentTimeMillis()
        )));
    }
    
    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        subscriptions.remove(session);
        pendingMessages.remove(session);
        LOG.info("WebSocket client disconnected: {}", session.getId());
        
        // Record telemetry
        if (telemetryService != null) {
            telemetryService.recordWsConnection(false);
        }
    }
    
    @OnError
    public void onError(Session session, Throwable error) {
        LOG.error("WebSocket error for session {}: {}", session.getId(), error.getMessage());
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        // Record telemetry
        if (telemetryService != null) {
            telemetryService.recordWsMessageIn();
        }
        
        try {
            Map<String, Object> request = mapper.readValue(message, Map.class);
            String action = (String) request.get("action");
            
            switch (action) {
                case "SUBSCRIBE":
                    handleSubscribe(session, request);
                    break;
                case "UNSUBSCRIBE":
                    handleUnsubscribe(session, request);
                    break;
                case "GET_SNAPSHOT":
                    handleGetSnapshot(session, request);
                    break;
                case "PING":
                    sendDirect(session, new Message("PONG", Map.of(
                        "timestamp", System.currentTimeMillis()
                    )));
                    break;
                case "REPLAY_TRADES":
                    handleTradeReplay(session, request);
                    break;
                default:
                    LOG.warn("Unknown action: {}", action);
            }
        } catch (Exception e) {
            LOG.error("Error processing message: {}", e.getMessage());
            sendDirect(session, new Message("ERROR", Map.of(
                "message", e.getMessage()
            )));
        }
    }
    
    private void handleSubscribe(Session session, Map<String, Object> request) {
        ClientSubscription sub = subscriptions.get(session);
        if (sub == null) return;
        
        String channel = (String) request.get("channel");
        List<String> symbols = (List<String>) request.get("symbols");
        
        switch (channel) {
            case "ORDERS":
                sub.subscribedOrders = true;
                if (symbols != null) sub.orderSymbols.addAll(symbols);
                break;
            case "TRADES":
                sub.subscribedTrades = true;
                if (symbols != null) sub.tradeSymbols.addAll(symbols);
                break;
            case "MARKET_DATA":
                sub.subscribedMarketData = true;
                if (symbols != null) sub.marketDataSymbols.addAll(symbols);
                break;
            case "POSITIONS":
                sub.subscribedPositions = true;
                break;
            case "ORDER_BOOK":
                sub.subscribedOrderBook = true;
                if (symbols != null) sub.orderBookSymbols.addAll(symbols);
                break;
            case "OPTIONS_PRICE":
                sub.subscribedOptionsPrice = true;
                if (symbols != null) sub.optionsPriceSymbols.addAll(symbols);
                break;
            case "ALL":
                sub.subscribedOrders = true;
                sub.subscribedTrades = true;
                sub.subscribedMarketData = true;
                sub.subscribedPositions = true;
                sub.subscribedOptionsPrice = true;
                break;
        }
        
        sendDirect(session, new Message("SUBSCRIBED", Map.of(
            "channel", channel,
            "symbols", symbols != null ? symbols : "all"
        )));
        
        LOG.info("Session {} subscribed to {}", session.getId(), channel);
    }
    
    private void handleUnsubscribe(Session session, Map<String, Object> request) {
        ClientSubscription sub = subscriptions.get(session);
        if (sub == null) return;
        
        String channel = (String) request.get("channel");
        
        switch (channel) {
            case "ORDERS":
                sub.subscribedOrders = false;
                sub.orderSymbols.clear();
                break;
            case "TRADES":
                sub.subscribedTrades = false;
                sub.tradeSymbols.clear();
                break;
            case "MARKET_DATA":
                sub.subscribedMarketData = false;
                sub.marketDataSymbols.clear();
                break;
            case "POSITIONS":
                sub.subscribedPositions = false;
                break;
            case "ORDER_BOOK":
                sub.subscribedOrderBook = false;
                sub.orderBookSymbols.clear();
                break;
            case "OPTIONS_PRICE":
                sub.subscribedOptionsPrice = false;
                sub.optionsPriceSymbols.clear();
                break;
        }
        
        sendDirect(session, new Message("UNSUBSCRIBED", Map.of("channel", channel)));
    }
    
    private void handleGetSnapshot(Session session, Map<String, Object> request) {
        String snapshotType = (String) request.get("type");
        
        switch (snapshotType) {
            case "MARKET_DATA":
                Collection<ReferenceDataService.MarketData> marketData = 
                    referenceDataService.getAllMarketData();
                sendDirect(session, new Message("SNAPSHOT_MARKET_DATA", Map.of(
                    "data", marketData,
                    "timestamp", System.currentTimeMillis()
                )));
                break;
            case "SECURITIES":
                Collection<ReferenceDataService.Security> securities = 
                    referenceDataService.getAllSecurities();
                sendDirect(session, new Message("SNAPSHOT_SECURITIES", Map.of(
                    "data", securities,
                    "timestamp", System.currentTimeMillis()
                )));
                break;
            case "RECENT_TRADES":
                List<TradeEntity> trades = tradeService.getRecentTrades(100);
                sendDirect(session, new Message("SNAPSHOT_TRADES", Map.of(
                    "data", trades,
                    "timestamp", System.currentTimeMillis()
                )));
                break;
            default:
                LOG.warn("Unknown snapshot type: {}", snapshotType);
        }
    }
    
    /**
     * G2-M5: Handle trade replay request on reconnect
     * - Sends last N trades for specified symbols or all symbols
     * - Supports custom count from client request
     */
    private void handleTradeReplay(Session session, Map<String, Object> request) {
        Object countObj = request.get("count");
        int count = countObj != null ? ((Number) countObj).intValue() : TRADE_REPLAY_COUNT;
        count = Math.min(count, 200); // Cap at 200 to prevent abuse
        
        List<String> symbols = (List<String>) request.get("symbols");
        
        LOG.info("Trade replay requested by session {} - count: {}, symbols: {}", 
                session.getId(), count, symbols);
        
        List<TradeEntity> tradesToReplay;
        
        if (symbols == null || symbols.isEmpty()) {
            // Replay all recent trades
            tradesToReplay = tradeService.getRecentTrades(count);
        } else {
            // Replay trades for specific symbols
            tradesToReplay = new ArrayList<>();
            int perSymbolCount = Math.max(1, count / symbols.size());
            
            for (String symbol : symbols) {
                List<TradeEntity> symbolTrades = tradeService.getTradesBySymbol(symbol);
                tradesToReplay.addAll(symbolTrades.stream()
                    .limit(perSymbolCount)
                    .collect(java.util.stream.Collectors.toList()));
            }
            
            // Sort all by timestamp descending and limit
            tradesToReplay.sort((a, b) -> {
                java.time.LocalDateTime aTime = a.getCreatedAt();
                java.time.LocalDateTime bTime = b.getCreatedAt();
                if (aTime == null && bTime == null) return 0;
                if (aTime == null) return 1;
                if (bTime == null) return -1;
                return bTime.compareTo(aTime);
            });
            if (tradesToReplay.size() > count) {
                tradesToReplay = tradesToReplay.subList(0, count);
            }
        }
        
        // Send replay header
        sendDirect(session, new Message("TRADE_REPLAY_START", Map.of(
            "count", tradesToReplay.size(),
            "timestamp", System.currentTimeMillis()
        )));
        
        // Send each trade
        for (TradeEntity trade : tradesToReplay) {
            long createdAtMs = trade.getCreatedAt() != null 
                ? trade.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() 
                : 0;
            sendDirect(session, new Message("TRADE_REPLAY", Map.of(
                "tradeId", trade.getTradeId(),
                "symbol", trade.getSymbol(),
                "price", trade.getPrice(),
                "quantity", trade.getQuantity(),
                "buyOrderId", trade.getBuyOrderId(),
                "sellOrderId", trade.getSellOrderId(),
                "aggressorSide", trade.getAggressorSide(),
                "tradeDate", trade.getTradeDate(),
                "tradeTime", createdAtMs,
                "isReplay", true
            )));
        }
        
        // Send replay complete
        sendDirect(session, new Message("TRADE_REPLAY_END", Map.of(
            "count", tradesToReplay.size(),
            "timestamp", System.currentTimeMillis()
        )));
        
        LOG.info("Trade replay completed for session {} - sent {} trades", 
                session.getId(), tradesToReplay.size());
    }
    
    // ================== Public Broadcast Methods ==================
    
    /**
     * Broadcast order update to subscribed clients
     */
    public void broadcastOrderUpdate(OrderEntity order) {
        Message msg = new Message("ORDER_UPDATE", Map.of(
            "orderId", order.getId(),
            "orderRefNumber", order.getOrderRefNumber(),
            "symbol", order.getSymbol(),
            "side", order.getSide(),
            "quantity", order.getQuantity(),
            "price", order.getPrice(),
            "status", order.getStatus(),
            "filledQty", order.getFilledQty() != null ? order.getFilledQty() : 0,
            "leavesQty", order.getLeavesQty() != null ? order.getLeavesQty() : order.getQuantity(),
            "timestamp", System.currentTimeMillis()
        ));
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedOrders) {
                if (sub.orderSymbols.isEmpty() || sub.orderSymbols.contains(order.getSymbol())) {
                    queueMessage(session, msg);
                }
            }
        }
    }
    
    /**
     * Broadcast trade execution to subscribed clients
     */
    public void broadcastTrade(TradeEntity trade) {
        Message msg = new Message("TRADE", Map.of(
            "tradeId", trade.getTradeId(),
            "symbol", trade.getSymbol(),
            "price", trade.getPrice(),
            "quantity", trade.getQuantity(),
            "buyOrderId", trade.getBuyOrderId(),
            "sellOrderId", trade.getSellOrderId(),
            "aggressorSide", trade.getAggressorSide(),
            "timestamp", System.currentTimeMillis()
        ));
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedTrades) {
                if (sub.tradeSymbols.isEmpty() || sub.tradeSymbols.contains(trade.getSymbol())) {
                    queueMessage(session, msg);
                }
            }
        }
    }
    
    /**
     * Broadcast market data update to subscribed clients
     */
    public void broadcastMarketData(String symbol, double lastPrice, double bid, double ask, 
                                    double change, double changePercent) {
        Message msg = new Message("MARKET_DATA", Map.of(
            "symbol", symbol,
            "lastPrice", lastPrice,
            "bid", bid,
            "ask", ask,
            "change", change,
            "changePercent", changePercent,
            "timestamp", System.currentTimeMillis()
        ));
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedMarketData) {
                if (sub.marketDataSymbols.isEmpty() || sub.marketDataSymbols.contains(symbol)) {
                    queueMessage(session, msg);
                }
            }
        }
    }
    
    /**
     * Broadcast order book update to subscribed clients
     */
    public void broadcastOrderBook(OrderBookManager.OrderBookSnapshot snapshot) {
        Message msg = new Message("ORDER_BOOK", Map.of(
            "symbol", snapshot.symbol,
            "bids", snapshot.bids,
            "asks", snapshot.asks,
            "timestamp", snapshot.timestamp
        ));
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedOrderBook) {
                if (sub.orderBookSymbols.isEmpty() || sub.orderBookSymbols.contains(snapshot.symbol)) {
                    queueMessage(session, msg);
                }
            }
        }
    }
    
    /**
     * Broadcast position update to subscribed clients
     */
    public void broadcastPosition(String clientId, String symbol, int quantity, 
                                  double avgCost, double marketValue, double pnl) {
        Message msg = new Message("POSITION", Map.of(
            "clientId", clientId,
            "symbol", symbol,
            "quantity", quantity,
            "avgCost", avgCost,
            "marketValue", marketValue,
            "pnl", pnl,
            "timestamp", System.currentTimeMillis()
        ));
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedPositions) {
                queueMessage(session, msg);
            }
        }
    }
    
    /**
     * G3-M4: Broadcast options price update to subscribed clients
     * - Triggered when underlying price changes from trade execution
     * - Contains fair price, Greeks, and IV
     */
    public void broadcastOptionsPrice(String symbol, String optionType,
                                      double spotPrice, double strikePrice,
                                      double fairPrice, double delta, double gamma,
                                      double theta, double vega, double rho,
                                      double impliedVolatility, double timeToExpiry) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", symbol);
        data.put("optionType", optionType);
        data.put("spotPrice", spotPrice);
        data.put("strikePrice", strikePrice);
        data.put("fairPrice", fairPrice);
        data.put("delta", delta);
        data.put("gamma", gamma);
        data.put("theta", theta);
        data.put("vega", vega);
        data.put("rho", rho);
        data.put("impliedVolatility", impliedVolatility);
        data.put("timeToExpiry", timeToExpiry);
        data.put("timestamp", System.currentTimeMillis());
        
        Message msg = new Message("OPTIONS_PRICE", data);
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedOptionsPrice) {
                if (sub.optionsPriceSymbols.isEmpty() || sub.optionsPriceSymbols.contains(symbol)) {
                    queueMessage(session, msg);
                }
            }
        }
    }
    
    /**
     * G3-M4: Broadcast full option chain update
     * - Contains all option prices for a symbol after trade execution
     */
    public void broadcastOptionPriceUpdate(String symbol, Map<String, Object> data) {
        Message msg = new Message("OPTION_CHAIN_UPDATE", data);
        
        for (Session session : sessions) {
            ClientSubscription sub = subscriptions.get(session);
            if (sub != null && sub.subscribedOptionsPrice) {
                if (sub.optionsPriceSymbols.isEmpty() || sub.optionsPriceSymbols.contains(symbol)) {
                    queueMessage(session, msg);
                }
            }
        }
    }
    
    // ================== Helper Methods ==================
    
    private void queueMessage(Session session, Message message) {
        List<Message> queue = pendingMessages.get(session);
        if (queue != null) {
            queue.add(message);
        }
    }
    
    private void flushPendingMessages() {
        for (Map.Entry<Session, List<Message>> entry : pendingMessages.entrySet()) {
            Session session = entry.getKey();
            List<Message> messages = entry.getValue();
            
            if (!messages.isEmpty() && session.isOpen()) {
                // Batch messages
                List<Message> batch = new ArrayList<>(messages);
                messages.clear();
                
                try {
                    if (batch.size() == 1) {
                        session.getBasicRemote().sendText(mapper.writeValueAsString(batch.get(0)));
                    } else {
                        session.getBasicRemote().sendText(mapper.writeValueAsString(
                            new Message("BATCH", Map.of("messages", batch))
                        ));
                    }
                } catch (IOException e) {
                    LOG.error("Error sending batch to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }
    
    private void sendDirect(Session session, Message message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendText(mapper.writeValueAsString(message));
                if (telemetryService != null) {
                    telemetryService.recordWsMessageOut();
                }
            } catch (IOException e) {
                LOG.error("Error sending to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
    
    public int getConnectedClients() {
        return sessions.size();
    }
    
    // ================== Inner Classes ==================
    
    private static class ClientSubscription {
        boolean subscribedOrders = false;
        boolean subscribedTrades = false;
        boolean subscribedMarketData = false;
        boolean subscribedPositions = false;
        boolean subscribedOrderBook = false;
        boolean subscribedOptionsPrice = false;
        
        Set<String> orderSymbols = ConcurrentHashMap.newKeySet();
        Set<String> tradeSymbols = ConcurrentHashMap.newKeySet();
        Set<String> marketDataSymbols = ConcurrentHashMap.newKeySet();
        Set<String> orderBookSymbols = ConcurrentHashMap.newKeySet();
        Set<String> optionsPriceSymbols = ConcurrentHashMap.newKeySet();
    }
    
    public static class Message {
        public String type;
        public Object data;
        public long timestamp;
        
        public Message() {}
        
        public Message(String type, Object data) {
            this.type = type;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
