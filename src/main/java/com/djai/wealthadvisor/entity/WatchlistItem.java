package com.djai.wealthadvisor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "watchlist_item", schema = "dj", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id",
		"instrument_key" }))
@Data
public class WatchlistItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "user_key", nullable = false, length = 255)
	private String userKey; // <-- REQUIRED (email)

	@Column(name = "instrument_key", nullable = false, length = 80)
	private String instrumentKey;

	@Column(name = "trading_symbol", length = 50)
	private String tradingSymbol;

	@Column(name = "name", length = 200)
	private String name;

	@Column(name = "exchange", length = 10)
	private String exchange;

	@Column(name = "created_at", updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
