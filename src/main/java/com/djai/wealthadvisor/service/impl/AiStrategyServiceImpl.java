package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.FinnhubClient;
import com.djai.wealthadvisor.dto.AiStrategyDto;
import com.djai.wealthadvisor.dto.CandleDto;
import com.djai.wealthadvisor.dto.CandleSeriesDto;
import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.service.AiStrategyService;
import com.djai.wealthadvisor.service.MarketCandleService;
import com.djai.wealthadvisor.service.MarketQuoteService;
import com.djai.wealthadvisor.util.TechnicalIndicators;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AiStrategyServiceImpl implements AiStrategyService {

    private final MarketQuoteService marketQuoteService;
    private final MarketCandleService marketCandleService;
    private final FinnhubClient finnhubClient;

    @Override
    public AiStrategyDto generate(AiStrategyDto req) {
        String instrumentKey = normalizeInstrumentKey(req.getInstrumentKey());

        // 1) Quote
        MarketQuoteDto q = marketQuoteService.getQuote(instrumentKey);

        String tradingSymbol = firstNonBlank(req.getTradingSymbol(), q != null ? q.getTradingSymbol() : null);
        String name = firstNonBlank(req.getName(), q != null ? q.getName() : null);
        String exchange = q != null ? q.getExchange() : null;

        // 2) Daily candles for indicators (enough for SMA200)
        LocalDate today = LocalDate.now();
        String to = today.toString();
        String from = today.minusDays(550).toString();

        CandleSeriesDto series = marketCandleService.getHistoricalCandles(
                instrumentKey, "days", 1, from, to, 500, "asc"
        );

        List<CandleDto> candles = (series != null && series.candles() != null) ? series.candles() : List.of();
        List<Double> closes = new ArrayList<>(candles.size());
        for (CandleDto c : candles) closes.add(c.close());

        Double sma20 = TechnicalIndicators.sma(closes, 20);
        Double sma50 = TechnicalIndicators.sma(closes, 50);
        Double sma200 = TechnicalIndicators.sma(closes, 200);
        Double rsi14 = TechnicalIndicators.rsi(closes, 14);
        TechnicalIndicators.Macd macd = TechnicalIndicators.macd(closes, 12, 26, 9);

        double support20 = calcSupport20(candles);
        double resistance20 = calcResistance20(candles);
        double vol20 = calcVolatility20Pct(closes);

        Decision decision = decide(q, sma50, sma200, rsi14, macd);
        int confidence = computeConfidence(decision.score, closes.size(), sma200 != null, vol20);

        Analyst analyst = fetchAnalyst(instrumentKey, tradingSymbol, q != null ? q.getLtp() : null);

        String strategyText = buildStrategyText(
                name, tradingSymbol, decision, confidence,
                sma50, sma200, rsi14, macd, support20, resistance20, vol20, analyst
        );

        List<String> drivers = buildDrivers(decision, sma50, sma200, rsi14, macd, analyst);

        // 3) Response (single flat DTO)
        AiStrategyDto out = new AiStrategyDto();
        out.setInstrumentKey(instrumentKey);
        out.setTradingSymbol(tradingSymbol);
        out.setName(name);
        out.setExchange(exchange);
        out.setAsOf(q != null ? q.getAsOf() : LocalDateTime.now());

        if (q != null) {
            out.setLtp(q.getLtp());
            out.setPrevClose(q.getPrevClose());
            out.setChange(q.getChange());
            out.setChangePercent(q.getChangePercent());
        }

        out.setRecommendation(decision.label);
        out.setConfidence(confidence);
        out.setHorizon(decision.horizon);
        out.setRiskLevel(riskLevel(vol20));
        out.setPersonalizedStrategy(strategyText);
        out.setKeyDrivers(drivers);

        out.setSma20(sma20);
        out.setSma50(sma50);
        out.setSma200(sma200);
        out.setRsi14(rsi14);

        if (macd != null) {
            out.setMacd(macd.macd());
            out.setMacdSignal(macd.signal());
            out.setMacdHistogram(macd.histogram());
        }

        out.setSupport20(Double.isFinite(support20) ? support20 : null);
        out.setResistance20(Double.isFinite(resistance20) ? resistance20 : null);
        out.setVolatility20Pct(Double.isFinite(vol20) ? vol20 : null);

        if (analyst.available) {
            out.setAnalystAvailable(true);
            out.setAnalystCount(analyst.analystCount);

            out.setTargetMean(analyst.targetMean);
            out.setTargetHigh(analyst.targetHigh);
            out.setTargetLow(analyst.targetLow);
            out.setUpsidePercent(analyst.upsidePercent);

            out.setAnalystPeriod(analyst.period);
            out.setStrongBuy(analyst.strongBuy);
            out.setBuy(analyst.buy);
            out.setHold(analyst.hold);
            out.setSell(analyst.sell);
            out.setStrongSell(analyst.strongSell);
        } else {
            out.setAnalystAvailable(false);
        }

        return out;
    }

    // ---------- Decision engine ----------

    private Decision decide(MarketQuoteDto q, Double sma50, Double sma200, Double rsi14, TechnicalIndicators.Macd macd) {
        double ltp = q != null ? q.getLtp() : Double.NaN;
        int score = 0;

        // Trend
        if (Double.isFinite(ltp) && sma50 != null) score += (ltp >= sma50) ? 1 : -1;
        if (sma50 != null && sma200 != null) score += (sma50 >= sma200) ? 1 : -1;

        // RSI
        if (rsi14 != null) {
            if (rsi14 >= 45 && rsi14 <= 65) score += 1;
            else if (rsi14 >= 75) score -= 1;
            else if (rsi14 <= 30) score += 1;
        }

        // MACD
        if (macd != null) score += (macd.macd() >= macd.signal()) ? 1 : -1;

        String label;
        if (score >= 3) label = "BUY";
        else if (score <= 0) label = "SELL";
        else label = "HOLD";

        String horizon = (sma50 != null && sma200 != null && sma50 >= sma200)
                ? "Long-term (3–12 months)"
                : "Swing (2–6 weeks)";

        return new Decision(label, score, horizon);
    }

    private int computeConfidence(int score, int candleCount, boolean hasSma200, double vol20Pct) {
        int c = 55;
        c += score * 6;
        if (candleCount < 80) c -= 10;
        if (!hasSma200) c -= 8;

        if (Double.isFinite(vol20Pct)) {
            if (vol20Pct > 4.5) c -= 8;
            else if (vol20Pct < 1.5) c += 4;
        }

        if (c > 85) c = 85;
        if (c < 45) c = 45;
        return c;
    }

    private static String riskLevel(double vol20Pct) {
        if (!Double.isFinite(vol20Pct) || vol20Pct <= 0) return "Medium";
        if (vol20Pct < 1.5) return "Low";
        if (vol20Pct < 3.5) return "Medium";
        return "High";
    }

    // ---------- Analyst (optional) ----------

    private Analyst fetchAnalyst(String instrumentKey, String tradingSymbol, Double ltp) {
        if (tradingSymbol == null || tradingSymbol.isBlank() || ltp == null || ltp <= 0) return Analyst.none();

        String finnhubSymbol = toFinnhubSymbol(instrumentKey, tradingSymbol);
        if (finnhubSymbol == null) return Analyst.none();

        var ptOpt = finnhubClient.getPriceTarget(finnhubSymbol);
        var recOpt = finnhubClient.getRecommendationTrend(finnhubSymbol);
        if (ptOpt.isEmpty() && recOpt.isEmpty()) return Analyst.none();

        Analyst a = new Analyst();
        a.available = true;

        ptOpt.ifPresent(pt -> {
            a.analystCount = pt.numberAnalysts();
            a.targetMean = pt.targetMean();
            a.targetHigh = pt.targetHigh();
            a.targetLow = pt.targetLow();
            a.upsidePercent = round2(((pt.targetMean() - ltp) / ltp) * 100.0);
        });

        recOpt.ifPresent(r -> {
            a.period = r.period();
            a.strongBuy = r.strongBuy();
            a.buy = r.buy();
            a.hold = r.hold();
            a.sell = r.sell();
            a.strongSell = r.strongSell();
        });

        return a;
    }

    private static String toFinnhubSymbol(String instrumentKey, String tradingSymbol) {
        String prefix = (instrumentKey != null && instrumentKey.contains("|"))
                ? instrumentKey.substring(0, instrumentKey.indexOf('|'))
                : "";
        prefix = prefix.toUpperCase(Locale.ROOT);

        if (prefix.startsWith("NSE_EQ")) return tradingSymbol + ".NS";
        if (prefix.startsWith("BSE_EQ")) return tradingSymbol + ".BO";
        return null; // skip indexes
    }

    // ---------- Strategy text ----------

    private static String buildStrategyText(
            String name,
            String symbol,
            Decision decision,
            int confidence,
            Double sma50,
            Double sma200,
            Double rsi14,
            TechnicalIndicators.Macd macd,
            double support20,
            double resistance20,
            double vol20,
            Analyst analyst
    ) {
        String display = (name != null && !name.isBlank()) ? name : (symbol != null ? symbol : "this stock");

        StringBuilder sb = new StringBuilder();
        sb.append("Our AI recommends a ").append(decision.label)
          .append(" for ").append(display);
        if (symbol != null && !symbol.isBlank()) sb.append(" (").append(symbol).append(")");
        sb.append(" based on technical trend and momentum signals.");

        List<String> signals = new ArrayList<>();
        if (sma50 != null && sma200 != null) signals.add("Trend: SMA50 is " + (sma50 >= sma200 ? "above" : "below") + " SMA200");
        if (rsi14 != null) signals.add("RSI(14) is around " + round2(rsi14));
        if (macd != null) signals.add("MACD is " + (macd.macd() >= macd.signal() ? "bullish" : "bearish"));

        if (!signals.isEmpty()) sb.append(" Top signals: ").append(String.join("; ", signals)).append(".");

        if (analyst.available && analyst.analystCount != null && analyst.analystCount > 0
                && analyst.targetMean != null && analyst.targetMean > 0
                && analyst.upsidePercent != null) {
            sb.append(" Analyst consensus: ").append(analyst.analystCount)
              .append(" analysts; mean target suggests ~")
              .append(round2(analyst.upsidePercent)).append("% potential upside.");
        }

        if (Double.isFinite(support20) && Double.isFinite(resistance20) && support20 > 0 && resistance20 > 0) {
            sb.append(" Near-term range: support ~").append(round2(support20))
              .append(", resistance ~").append(round2(resistance20)).append(".");
        }

        if (Double.isFinite(vol20) && vol20 > 0) {
            sb.append(" Volatility (20D): ~").append(round2(vol20)).append("%.");
        }

        sb.append(" Horizon: ").append(decision.horizon)
          .append(". Risk: ").append(riskLevel(vol20))
          .append(". Confidence: ").append(confidence).append("/100.")
          .append(" Not financial advice.");

        return sb.toString();
    }

    private static List<String> buildDrivers(Decision decision, Double sma50, Double sma200, Double rsi14,
                                            TechnicalIndicators.Macd macd, Analyst analyst) {
        List<String> out = new ArrayList<>();

        if (sma50 != null && sma200 != null) {
            out.add("Trend is " + (sma50 >= sma200 ? "positive" : "weak") + " (SMA50 " + (sma50 >= sma200 ? ">" : "<") + " SMA200)");
        }
        if (rsi14 != null) {
            if (rsi14 >= 65) out.add("Momentum is strong (RSI " + round2(rsi14) + ")");
            else if (rsi14 <= 35) out.add("Oversold zone (RSI " + round2(rsi14) + ") — bounce possible, risk higher");
            else out.add("Momentum is healthy (RSI " + round2(rsi14) + ")");
        }
        if (macd != null) out.add("MACD is " + (macd.macd() >= macd.signal() ? "bullish" : "bearish") + " (MACD vs signal)");
        if (analyst.available && analyst.analystCount != null && analyst.analystCount > 0 && analyst.upsidePercent != null) {
            out.add("Analyst mean target implies ~" + round2(analyst.upsidePercent) + "% upside (" + analyst.analystCount + " analysts)");
        }

        if (out.isEmpty()) out.add("Signals are mixed; model suggests " + decision.label + " with moderate confidence");

        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    // ---------- Helpers ----------

    private static double calcSupport20(List<CandleDto> candles) {
        if (candles == null || candles.isEmpty()) return Double.NaN;
        int n = Math.min(20, candles.size());
        double min = Double.POSITIVE_INFINITY;
        for (int i = candles.size() - n; i < candles.size(); i++) min = Math.min(min, candles.get(i).low());
        return min == Double.POSITIVE_INFINITY ? Double.NaN : min;
    }

    private static double calcResistance20(List<CandleDto> candles) {
        if (candles == null || candles.isEmpty()) return Double.NaN;
        int n = Math.min(20, candles.size());
        double max = Double.NEGATIVE_INFINITY;
        for (int i = candles.size() - n; i < candles.size(); i++) max = Math.max(max, candles.get(i).high());
        return max == Double.NEGATIVE_INFINITY ? Double.NaN : max;
    }

    private static double calcVolatility20Pct(List<Double> closes) {
        if (closes == null || closes.size() < 22) return Double.NaN;

        int n = 20;
        int start = closes.size() - (n + 1);
        List<Double> returns = new ArrayList<>(n);

        for (int i = start + 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1);
            double cur = closes.get(i);
            if (prev <= 0) continue;
            returns.add((cur - prev) / prev);
        }

        if (returns.size() < 10) return Double.NaN;

        double mean = 0;
        for (double r : returns) mean += r;
        mean /= returns.size();

        double var = 0;
        for (double r : returns) {
            double d = r - mean;
            var += d * d;
        }
        var /= returns.size();

        return Math.sqrt(var) * 100.0;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String normalizeInstrumentKey(String instrumentKey) {
        if (instrumentKey == null) return null;
        String trimmed = instrumentKey.trim();
        int pipe = trimmed.indexOf('|');
        if (pipe <= 0) return trimmed;

        String left = trimmed.substring(0, pipe).toUpperCase(Locale.ROOT);
        String right = trimmed.substring(pipe + 1);

        // For EQ keys like INE..., normalize uppercase to avoid mismatches.
        if (left.endsWith("_EQ") && right.toUpperCase(Locale.ROOT).startsWith("INE")) {
            right = right.toUpperCase(Locale.ROOT);
        }
        // Don't touch index names like "Nifty 50".
        return left + "|" + right;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Decision(String label, int score, String horizon) {}

    private static class Analyst {
        boolean available;
        Integer analystCount;
        Double targetMean;
        Double targetHigh;
        Double targetLow;
        Double upsidePercent;
        String period;
        Integer strongBuy, buy, hold, sell, strongSell;

        static Analyst none() {
            Analyst a = new Analyst();
            a.available = false;
            return a;
        }
    }
}
