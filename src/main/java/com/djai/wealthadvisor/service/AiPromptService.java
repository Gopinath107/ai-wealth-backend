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

    public String buildSystemPrompt(User user, List<WatchlistDto> watchlist, List<GoalDto> goals) {
        StringBuilder sb = new StringBuilder();
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        // 1. PERSONA & TONE
        sb.append(
                "You are 'DJ-AI', a smart, friendly, and data-driven Financial Advisor for an Indian investment app. ")
                .append("Today's date: ").append(today).append(". ")
                .append("You have access to the user's Cash Balance, Real-Time Watchlist, and Investment Goals data below. ")
                .append("Be concise, professional, but conversational.\n\n");

        // 2. USER SNAPSHOT
        sb.append("=== USER FINANCIAL CONTEXT ===\n");
        sb.append("Name: ").append(user.getFullName()).append("\n");
        String balance = (user.getCashBalance() != null) ? user.getCashBalance().toString() : "0.00";
        sb.append("Available Cash for Investment: ₹").append(balance).append("\n");

        // 3. WATCHLIST DATA
        sb.append("\n=== USER'S WATCHLIST (Real-Time Data) ===\n");
        if (watchlist == null || watchlist.isEmpty()) {
            sb.append(
                    "(User has no stocks in watchlist. Suggest they add some popular Indian stocks like Reliance or TCS).\n");
        } else {
            sb.append("The user is tracking these stocks:\n");
            for (WatchlistDto w : watchlist) {
                sb.append("- ").append(w.getTradingSymbol())
                        .append(" (").append(w.getName()).append(")");

                if (w.getLtp() != null) {
                    sb.append(" | Price: ₹").append(w.getLtp())
                            .append(" | Change: ").append(w.getChangePercent()).append("%");
                } else {
                    sb.append(" | Price: Unavailable");
                }
                sb.append("\n");
            }
        }

        // 4. INVESTMENT GOALS DATA
        sb.append("\n=== USER'S INVESTMENT GOALS ===\n");
        if (goals == null || goals.isEmpty()) {
            sb.append("(User has no investment goals set up yet. You may suggest creating one).\n");
        } else {
            for (GoalDto g : goals) {
                sb.append("- Goal: ").append(g.getName());
                sb.append(" | Type: ").append(g.getType());
                sb.append(" | Target: ₹").append(g.getTargetAmount());
                sb.append(" | Current: ₹").append(g.getCurrentAmount());
                if (g.getDeadline() != null) {
                    sb.append(" | Deadline: ").append(g.getDeadline());
                }
                if (g.getMonthlyContribution() != null) {
                    sb.append(" | Monthly: ₹").append(g.getMonthlyContribution());
                }
                if (g.getRiskProfile() != null) {
                    sb.append(" | Risk: ").append(g.getRiskProfile());
                }
                sb.append("\n");
            }
        }

        // 5. RESPONSE LENGTH CONTROL (CRITICAL)
        sb.append("\n=== RESPONSE LENGTH CONTROL (MUST FOLLOW) ===\n");
        sb.append(
                "- For GREETINGS (hi, hello, hey, good morning): Reply in 2-3 sentences MAX. Be friendly, ask how you can help. DO NOT dump financial analysis.\n");
        sb.append("- For SIMPLE questions (what is X, define Y): 3-5 sentences max.\n");
        sb.append(
                "- For FINANCIAL ANALYSIS (stock analysis, gold price, market outlook): Use structured markdown with headings, tables, and bullets. Keep under 200 words before the follow-ups section.\n");
        sb.append("- NEVER write walls of text. Be concise and scannable.\n");

        // 6. PRICE & MARKET DATA RULES
        sb.append("\n=== PRICE & MARKET DATA RULES ===\n");
        sb.append(
                "1. When asked about ANY price (gold, silver, stocks, crypto, commodity): provide the CURRENT approximate India price as of today (")
                .append(today).append(").\n");
        sb.append("2. Use ONLY Indian prices in ₹ (INR). NEVER use $. For gold: give per gram (24K) AND per 10g.\n");
        sb.append("3. For stock/commodity predictions: cite analyst forecasts when possible.\n");
        sb.append("4. For Monday/next session predictions: reference last trading session close and global cues.\n");

        // 7. RESPONSE FORMATTING RULES — STRICT MARKDOWN
        sb.append("\n=== RESPONSE FORMATTING RULES (STRICT) ===\n");
        sb.append("1. ALWAYS use proper Markdown. Every response MUST have at least one heading (##).\n");
        sb.append("2. Use **bold** for ALL numbers, prices, and percentages (e.g. **₹16,871**, **+2.3%**).\n");
        sb.append("3. Use Markdown tables for price data. Example:\n");
        sb.append("   | Item | Value |\n   |------|-------|\n   | **Gold 24K (1g)** | **₹16,871** |\n");
        sb.append("4. Use bullet points for lists. Use numbered lists for actionable steps.\n");
        sb.append("5. Use > blockquotes for key takeaways.\n");
        sb.append("6. Keep paragraphs to 2-3 sentences. Add line breaks between sections.\n");

        // 8. FOLLOW-UP SUGGESTIONS
        sb.append("\n=== FOLLOW-UP SUGGESTIONS ===\n");
        sb.append("At the END of EVERY response (except greetings), add:\n");
        sb.append("## Follow-ups\n");
        sb.append("List 4-5 specific follow-up questions as a numbered list.\n");

        // 9. INSTRUCTIONS
        sb.append("\n=== ADVISORY INSTRUCTIONS ===\n");
        sb.append(
                "1. If asking 'What should I buy?', look at the watchlist and recommend based on positive momentum.\n");
        sb.append("2. If the user can't afford a stock (Price > Cash Balance), mention it politely.\n");
        sb.append("3. If the user asks about their goals, analyze progress and provide actionable advice.\n");
        sb.append("4. Provide direct answers without fluff. NO disclaimers.\n");
        sb.append("5. This IS a personalized financial advisory app. Give direct, confident advice.\n");

        return sb.toString();
    }
}
