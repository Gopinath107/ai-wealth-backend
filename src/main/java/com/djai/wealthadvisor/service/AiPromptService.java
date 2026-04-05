package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.dto.WatchlistDto;
import com.djai.wealthadvisor.entity.User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class AiPromptService {

    /**
     * Builds a COMPACT system prompt that stays well under 1500 tokens.
     * Key rule: every field is one line, no padding or repeated instructions.
     */
    public String buildSystemPrompt(User user, List<WatchlistDto> watchlist, List<GoalDto> goals) {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        String balance = (user.getCashBalance() != null) ? user.getCashBalance().toPlainString() : "0";

        StringBuilder sb = new StringBuilder(1200);

        // ── PERSONA (1 line) ───────────────────────────────────────────────────
        sb.append("You are DJ-AI, a concise Indian financial advisor. Date: ").append(today)
          .append(". Cash available: ₹").append(balance).append(".\n");

        // ── WATCHLIST (max 5 items, 1 line each) ───────────────────────────────
        if (watchlist != null && !watchlist.isEmpty()) {
            sb.append("Watchlist:");
            int limit = Math.min(watchlist.size(), 5);
            for (int i = 0; i < limit; i++) {
                WatchlistDto w = watchlist.get(i);
                sb.append(" ").append(w.getTradingSymbol());
                if (w.getLtp() != null) sb.append("=₹").append(w.getLtp());
                if (i < limit - 1) sb.append(",");
            }
            sb.append(".\n");
        }

        // ── GOALS (max 3 items, 1 line each) ──────────────────────────────────
        if (goals != null && !goals.isEmpty()) {
            sb.append("Goals:");
            int limit = Math.min(goals.size(), 3);
            for (int i = 0; i < limit; i++) {
                GoalDto g = goals.get(i);
                sb.append(" ").append(g.getName())
                  .append("(target=₹").append(g.getTargetAmount())
                  .append(",current=₹").append(g.getCurrentAmount()).append(")");
                if (i < limit - 1) sb.append(";");
            }
            sb.append(".\n");
        }

        // ── RULES (single compact block) ──────────────────────────────────────
        sb.append("Rules: Use ₹ (INR) only. Reply concisely in Markdown with ##headings. ")
          .append("For greetings: 2 sentences. For analysis: <150 words + ##Follow-ups with 3 questions. ")
          .append("Always give bold prices like **₹16,871**. No disclaimers.");

        return sb.toString();
    }
}
