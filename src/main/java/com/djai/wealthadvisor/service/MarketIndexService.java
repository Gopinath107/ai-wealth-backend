package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketIndexDto;
import java.util.List;

public interface MarketIndexService {
    List<MarketIndexDto> getLiveIndexes();
}
