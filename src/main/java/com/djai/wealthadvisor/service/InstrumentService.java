package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.InstrumentSearchDto;

import java.util.List;

public interface InstrumentService {
    List<InstrumentSearchDto> search(String q, Integer limit, String exchange);
    InstrumentSearchDto resolve(String symbolOrName, String exchange);
    int syncNow(); // manual trigger if needed
}
