package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketQuoteDto;

import java.util.List;

public interface MarketQuoteService {
    MarketQuoteDto getQuote(String instrumentKey);
    List<MarketQuoteDto> getQuotes(List<String> instrumentKeys);
}
