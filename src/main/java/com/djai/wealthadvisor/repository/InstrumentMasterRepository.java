package com.djai.wealthadvisor.repository;

import com.djai.wealthadvisor.entity.InstrumentMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InstrumentMasterRepository extends JpaRepository<InstrumentMaster, String> {

	Optional<InstrumentMaster> findFirstByTradingSymbolIgnoreCaseAndExchangeIgnoreCase(String tradingSymbol,
			String exchange);

	@Query(value = """
			SELECT * FROM dj.instrument_master
			WHERE
			  (LOWER(trading_symbol) LIKE LOWER(CONCAT(:q, '%'))
			   OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%')))
			  AND (:exchange IS NULL OR LOWER(exchange) = LOWER(:exchange))
			  AND segment IN ('NSE_EQ','BSE_EQ','NSE_INDEX','BSE_INDEX')
			ORDER BY
			  CASE
			    WHEN LOWER(trading_symbol) = LOWER(:q) THEN 0
			    WHEN LOWER(trading_symbol) LIKE LOWER(CONCAT(:q, '%')) THEN 1
			    ELSE 2
			  END,
			  trading_symbol
			LIMIT :limit
			""", nativeQuery = true)
	List<InstrumentMaster> search(@Param("q") String q, @Param("exchange") String exchange, @Param("limit") int limit);
	
	@Query(value = """
		    SELECT * FROM dj.instrument_master
		    WHERE segment IN ('NSE_INDEX','BSE_INDEX')
		      AND (LOWER(trading_symbol) = LOWER(:q) OR LOWER(name) = LOWER(:q))
		    LIMIT 1
		    """, nativeQuery = true)
		Optional<InstrumentMaster> findIndexExact(@Param("q") String q);

		@Query(value = """
		    SELECT * FROM dj.instrument_master
		    WHERE segment IN ('NSE_INDEX','BSE_INDEX')
		      AND (LOWER(trading_symbol) LIKE LOWER(CONCAT('%', :q, '%'))
		           OR LOWER(name) LIKE LOWER(CONCAT('%', :q, '%')))
		    ORDER BY LENGTH(name) ASC
		    LIMIT 1
		    """, nativeQuery = true)
		Optional<InstrumentMaster> findIndexLike(@Param("q") String q);
}
