package com.djai.wealthadvisor.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "instrument_master", schema = "dj")
public class InstrumentMaster {

    @Id
    @Column(name = "instrument_key", length = 60)
    private String instrumentKey;

    @Column(name = "trading_symbol", length = 50, nullable = false)
    private String tradingSymbol;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "exchange", length = 10)
    private String exchange;

    @Column(name = "isin", length = 20)
    private String isin;

    @Column(name = "segment", length = 20)
    private String segment; // NSE_EQ, NSE_INDEX, BSE_EQ, BSE_INDEX...

    @Column(name = "instrument_type", length = 20)
    private String instrumentType; // EQ / INDEX / FUT / CE / PE ...

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }

   
}
