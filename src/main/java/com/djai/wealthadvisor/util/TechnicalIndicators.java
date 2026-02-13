package com.djai.wealthadvisor.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny, dependency-free indicator helper.
 * Uses close prices in chronological order (oldest -> newest).
 */
public final class TechnicalIndicators {

    private TechnicalIndicators() {}

    public static Double sma(List<Double> closes, int period) {
        if (closes == null || closes.isEmpty() || period <= 0) return null;
        if (closes.size() < period) return null;
        double sum = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) sum += closes.get(i);
        return sum / period;
    }

    public static Double rsi(List<Double> closes, int period) {
        if (closes == null || closes.size() < period + 1 || period <= 0) return null;

        double gain = 0, loss = 0;
        int start = closes.size() - (period + 1);

        for (int i = start + 1; i < closes.size(); i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            if (diff >= 0) gain += diff;
            else loss += -diff;
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public static Macd macd(List<Double> closes, int fast, int slow, int signal) {
        if (closes == null || closes.size() < slow + signal) return null;

        List<Double> emaFast = emaSeries(closes, fast);
        List<Double> emaSlow = emaSeries(closes, slow);
        if (emaFast.isEmpty() || emaSlow.isEmpty()) return null;

        int n = Math.min(emaFast.size(), emaSlow.size());
        List<Double> macdLine = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            macdLine.add(emaFast.get(emaFast.size() - n + i) - emaSlow.get(emaSlow.size() - n + i));
        }

        List<Double> signalSeries = emaSeries(macdLine, signal);
        if (signalSeries.isEmpty()) return null;

        double macdLast = macdLine.get(macdLine.size() - 1);
        double signalLast = signalSeries.get(signalSeries.size() - 1);
        double hist = macdLast - signalLast;

        return new Macd(macdLast, signalLast, hist);
    }

    private static List<Double> emaSeries(List<Double> values, int period) {
        if (values == null || values.size() < period || period <= 0) return List.of();

        double k = 2.0 / (period + 1.0);
        List<Double> out = new ArrayList<>(values.size());

        double ema = 0;
        for (int i = 0; i < period; i++) ema += values.get(i);
        ema /= period;

        for (int i = 0; i < period - 1; i++) out.add(Double.NaN);
        out.add(ema);

        for (int i = period; i < values.size(); i++) {
            ema = (values.get(i) - ema) * k + ema;
            out.add(ema);
        }

        int firstReal = 0;
        while (firstReal < out.size() && out.get(firstReal).isNaN()) firstReal++;
        return out.subList(firstReal, out.size());
    }

    public record Macd(double macd, double signal, double histogram) {}
}
