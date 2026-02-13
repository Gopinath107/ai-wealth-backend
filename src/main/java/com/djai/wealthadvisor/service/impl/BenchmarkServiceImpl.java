package com.djai.wealthadvisor.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.service.BenchmarkService;
import com.djai.wealthadvisor.service.MarketQuoteService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BenchmarkServiceImpl implements BenchmarkService {

	private final InstrumentMasterRepository instrumentRepo;
	private final MarketQuoteService marketQuoteService;

	@Override
	public Map<String, MarketQuoteDto> getBenchmarks() {
		String nifty50Key = resolveIndexKey(List.of("Nifty 50", "NIFTY 50", "NIFTY"));
		String niftyBankKey = resolveIndexKey(List.of("Nifty Bank", "NIFTY BANK", "BANKNIFTY", "NIFTY BANK INDEX"));
		String sensexKey = resolveIndexKey(List.of("SENSEX", "Sensex"));

		Map<String, MarketQuoteDto> out = new LinkedHashMap<>();
		out.put("NIFTY_50", nifty50Key != null ? marketQuoteService.getQuote(nifty50Key) : null);
		out.put("NIFTY_BANK", niftyBankKey != null ? marketQuoteService.getQuote(niftyBankKey) : null);
		out.put("SENSEX", sensexKey != null ? marketQuoteService.getQuote(sensexKey) : null);

		return out;
	}

	private String resolveIndexKey(List<String> aliases) {
		for (String a : aliases) {
			var exact = instrumentRepo.findIndexExact(a);
			if (exact.isPresent())
				return exact.get().getInstrumentKey();
		}
		for (String a : aliases) {
			var like = instrumentRepo.findIndexLike(a);
			if (like.isPresent())
				return like.get().getInstrumentKey();
		}
		return null;
	}
}
