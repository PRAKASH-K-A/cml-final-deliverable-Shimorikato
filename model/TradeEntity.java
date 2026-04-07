package com.helesto.model;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * G2-M3: Trade Entity
 * - Trade data generated from order matches
 * - Links to both sides of the trade
 */
@Entity
@Table(name = "trades")
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "trade_id", unique = true, nullable = false)
    private String tradeId;
    
    @Column(name = "exec_id")
    private String execId;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private Double price;
    
    @Column(nullable = false)
    private Integer quantity;
    
    // Buy side
    @Column(name = "buy_order_id")
    private String buyOrderId;
    
    @Column(name = "buy_client_id")
    private String buyClientId;
    
    @Column(name = "buy_cl_ord_id")
    private String buyClOrdId;
    
    // Sell side
    @Column(name = "sell_order_id")
    private String sellOrderId;
    
    @Column(name = "sell_client_id")
    private String sellClientId;
    
    @Column(name = "sell_cl_ord_id")
    private String sellClOrdId;
    
    // Trade metadata
    @Column(name = "trade_type")
    private String tradeType = "REGULAR"; // REGULAR, CROSS, AUCTION
    
    @Column(name = "aggressor_side")
    private String aggressorSide; // Which side was the taker
    
    @Column(name = "trade_date")
    private String tradeDate;
    
    @Column(name = "settlement_date")
    private String settlementDate;
    
    @Column(name = "trade_status")
    private String tradeStatus = "CONFIRMED"; // CONFIRMED, CANCELED, BUSTED
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getTradeId() {
        return tradeId;
    }
    
    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }
    
    public String getExecId() {
        return execId;
    }
    
    public void setExecId(String execId) {
        this.execId = execId;
    }
    
    public String getSymbol() {
        return symbol;
    }
    
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    
    public Double getPrice() {
        return price;
    }
    
    public void setPrice(Double price) {
        this.price = price;
    }
    
    public Integer getQuantity() {
        return quantity;
    }
    
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
    
    public String getBuyOrderId() {
        return buyOrderId;
    }
    
    public void setBuyOrderId(String buyOrderId) {
        this.buyOrderId = buyOrderId;
    }
    
    public String getBuyClientId() {
        return buyClientId;
    }
    
    public void setBuyClientId(String buyClientId) {
        this.buyClientId = buyClientId;
    }
    
    public String getBuyClOrdId() {
        return buyClOrdId;
    }
    
    public void setBuyClOrdId(String buyClOrdId) {
        this.buyClOrdId = buyClOrdId;
    }
    
    public String getSellOrderId() {
        return sellOrderId;
    }
    
    public void setSellOrderId(String sellOrderId) {
        this.sellOrderId = sellOrderId;
    }
    
    public String getSellClientId() {
        return sellClientId;
    }
    
    public void setSellClientId(String sellClientId) {
        this.sellClientId = sellClientId;
    }
    
    public String getSellClOrdId() {
        return sellClOrdId;
    }
    
    public void setSellClOrdId(String sellClOrdId) {
        this.sellClOrdId = sellClOrdId;
    }
    
    public String getTradeType() {
        return tradeType;
    }
    
    public void setTradeType(String tradeType) {
        this.tradeType = tradeType;
    }
    
    public String getAggressorSide() {
        return aggressorSide;
    }
    
    public void setAggressorSide(String aggressorSide) {
        this.aggressorSide = aggressorSide;
    }
    
    public String getTradeDate() {
        return tradeDate;
    }
    
    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }
    
    public String getSettlementDate() {
        return settlementDate;
    }
    
    public void setSettlementDate(String settlementDate) {
        this.settlementDate = settlementDate;
    }
    
    public String getTradeStatus() {
        return tradeStatus;
    }
    
    public void setTradeStatus(String tradeStatus) {
        this.tradeStatus = tradeStatus;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    @Override
    public String toString() {
        return "TradeEntity{" +
                "id=" + id +
                ", tradeId='" + tradeId + '\'' +
                ", symbol='" + symbol + '\'' +
                ", price=" + price +
                ", quantity=" + quantity +
                ", buyOrderId='" + buyOrderId + '\'' +
                ", sellOrderId='" + sellOrderId + '\'' +
                ", tradeStatus='" + tradeStatus + '\'' +
                '}';
    }
}
