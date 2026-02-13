package com.djai.wealthadvisor.service;

import java.util.List;

import com.djai.wealthadvisor.dto.MarketNewsDto;

public interface MarketNewsService {
    List<MarketNewsDto> feed(String tab, Integer limit, Integer page);
    List<MarketNewsDto> search(String q, Integer limit);
}
