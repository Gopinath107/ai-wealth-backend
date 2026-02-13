package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.UpstoxLtpV3Client;
import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.service.MarketQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketQuoteServiceImpl implements MarketQuoteService {

    private final UpstoxLtpV3Client upstoxLtpV3Client;
    private final InstrumentMasterRepository instrumentRepo;

    @Override
    public MarketQuoteDto getQuote(String instrumentKey) {
        List<MarketQuoteDto> list = getQuotes(List.of(instrumentKey));
        return list.isEmpty()
                ? MarketQuoteDto.builder().instrumentKey(instrumentKey).ltp(0).prevClose(0).change(0).changePercent(0).build()
                : list.get(0);
    }

    @Override
    public List<MarketQuoteDto> getQuotes(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return List.of();

        final int CHUNK = 300;
        List<MarketQuoteDto> out = new ArrayList<>();

        for (int i = 0; i < instrumentKeys.size(); i += CHUNK) {
            List<String> chunk = instrumentKeys.subList(i, Math.min(i + CHUNK, instrumentKeys.size()));

            // ✅ Upstox snapshots keyed by instrument_token (instrumentKey)
            Map<String, UpstoxLtpV3Client.LtpSnapshot> snaps = upstoxLtpV3Client.getLtpByInstrumentKey(chunk);

            // ✅ Load metadata in ONE DB call (not N calls)
            Map<String, InstrumentMaster> meta = instrumentRepo.findAllById(chunk).stream()
                    .collect(Collectors.toMap(
                            InstrumentMaster::getInstrumentKey,
                            Function.identity(),
                            (a, b) -> a
                    ));

            for (String key : chunk) {
                UpstoxLtpV3Client.LtpSnapshot s = snaps.get(key);
                InstrumentMaster im = meta.get(key);

                String tradingSymbol = im != null ? im.getTradingSymbol() : null;
                String name = im != null ? im.getName() : null;
                String exchange = im != null ? im.getExchange() : detectExchange(key);

                if (s == null) {
                    out.add(MarketQuoteDto.builder()
                            .instrumentKey(key)
                            .tradingSymbol(tradingSymbol)
                            .name(name)
                            .exchange(exchange)
                            .ltp(0).prevClose(0).change(0).changePercent(0)
                            .build());
                } else {
                    out.add(MarketQuoteDto.builder()
                            .instrumentKey(key)
                            .tradingSymbol(tradingSymbol)
                            .name(name)
                            .exchange(exchange)
                            .ltp(round2(s.getLastPrice()))
                            .prevClose(round2(s.getClosePrice()))
                            .change(round2(s.getChange()))
                            .changePercent(round2(s.getChangePercent()))
                            .asOf(s.getAsOf())
                            .build());
                }
            }
        }

        return out;
    }

    private String detectExchange(String instrumentKey) {
        if (instrumentKey == null) return "UNKNOWN";
        if (instrumentKey.startsWith("NSE_")) return "NSE";
        if (instrumentKey.startsWith("BSE_")) return "BSE";
        return "UNKNOWN";
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
