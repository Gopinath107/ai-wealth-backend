package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketFeedRowDto;
import java.util.List;

public interface MarketFeedService {

    // old calls still work
    default List<MarketFeedRowDto> getFeed(int limit, String q) {
        return getFeed(limit, q, false);
    }

    // ✅ new
    List<MarketFeedRowDto> getFeed(int limit, String q, boolean includeMarketCap);
}
