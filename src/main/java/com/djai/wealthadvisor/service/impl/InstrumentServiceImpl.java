package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.UpstoxInstrumentFileClient;
import com.djai.wealthadvisor.dto.InstrumentSearchDto;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.service.InstrumentService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InstrumentServiceImpl implements InstrumentService {

    private final InstrumentMasterRepository repo;
    private final UpstoxInstrumentFileClient upstoxInstrumentFileClient;

    public InstrumentServiceImpl(InstrumentMasterRepository repo,
                                 UpstoxInstrumentFileClient upstoxInstrumentFileClient) {
        this.repo = repo;
        this.upstoxInstrumentFileClient = upstoxInstrumentFileClient;
    }

    @Override
    public List<InstrumentSearchDto> search(String q, Integer limit, String exchange) {
        if (q == null || q.isBlank()) return List.of();
        int lim = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);

        return repo.search(q.trim(), (exchange == null || exchange.isBlank()) ? null : exchange.trim(), lim)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public InstrumentSearchDto resolve(String symbolOrName, String exchange) {
        if (symbolOrName == null || symbolOrName.isBlank()) return null;

        // First try exact trading symbol match (best UX)
        if (exchange != null && !exchange.isBlank()) {
            return repo.findFirstByTradingSymbolIgnoreCaseAndExchangeIgnoreCase(symbolOrName.trim(), exchange.trim())
                    .map(this::toDto)
                    .orElseGet(() -> {
                        List<InstrumentSearchDto> hits = search(symbolOrName, 1, exchange);
                        return hits.isEmpty() ? null : hits.get(0);
                    });
        }

        List<InstrumentSearchDto> hits = search(symbolOrName, 1, null);
        return hits.isEmpty() ? null : hits.get(0);
    }

    @Override
    public int syncNow() {
        return upstoxInstrumentFileClient.downloadAndUpsertEqAndIndex();
    }

    private InstrumentSearchDto toDto(InstrumentMaster im) {
        return new InstrumentSearchDto(
                im.getInstrumentKey(),
                im.getTradingSymbol(),
                im.getName(),
                im.getExchange(),
                im.getSegment(),
                im.getInstrumentType()
        );
    }
}
