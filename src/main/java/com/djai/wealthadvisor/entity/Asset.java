package com.djai.wealthadvisor.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Entity
@Table(name = "assets", schema = "dj", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "symbol"})
})
@Data
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String symbol; // e.g., "AAPL"

    private String name;

    @Column(nullable = false, precision = 15, scale = 5)
    private BigDecimal quantity;

    @Column(name = "avg_buy_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal avgBuyPrice;

    @Column(name = "asset_type")
    private String assetType; // Stock, Crypto
}