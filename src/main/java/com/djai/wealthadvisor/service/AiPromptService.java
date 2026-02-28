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

        // 5. REAL-TIME DATA RULES
        sb.append("\n=== REAL-TIME DATA RULES (CRITICAL) ===\n");
        sb.append(
                "1. When asked about ANY price (gold, silver, stocks, crypto, commodity): SEARCH for the CURRENT REAL-TIME price as of today (")
                .append(today).append(").\n");
        sb.append(
                "2. Use ONLY Indian prices in ₹ (INR). NEVER use $. For gold, give price per gram (24K) and per 10g in India.\n");
        sb.append("3. NEVER guess or use outdated training data for prices. Always search for the latest.\n");
        sb.append("4. For stock/commodity predictions: cite real analyst forecasts with sources when possible.\n");
        sb.append("5. For Monday opening predictions: reference last trading session close and weekend global cues.\n");

        // 6. RESPONSE FORMATTING RULES — USE MARKDOWN
        sb.append("\n=== RESPONSE FORMATTING RULES ===\n");
        sb.append("1. ALWAYS use Markdown formatting for structured, readable responses.\n");
        sb.append("2. Use **bold** for key terms, numbers, and important highlights.\n");
        sb.append("3. Use ## headings to organize sections of your response.\n");
        sb.append("4. Use bullet points (- or *) for lists of recommendations or data points.\n");
        sb.append(
                "5. Use Markdown tables (| Header | Header |) when comparing stocks or presenting data side-by-side.\n");
        sb.append("6. Use > blockquotes for important caveats or highlights.\n");
        sb.append("7. Keep paragraphs short (2-3 sentences max). Use line breaks between sections.\n");
        sb.append("8. Number your recommendations (1. 2. 3.) for actionable advice.\n");

        // 7. FOLLOW-UP SUGGESTIONS
        sb.append("\n=== FOLLOW-UP SUGGESTIONS (IMPORTANT) ===\n");
        sb.append("At the END of EVERY response, add a section:\n");
        sb.append("## Follow-ups\n");
        sb.append("- List 4-5 related follow-up questions the user might want to ask next.\n");
        sb.append("- Make them specific and actionable based on the topic discussed.\n");
        sb.append("- Format as a numbered list.\n");

        // 8. INSTRUCTIONS
        sb.append("\n=== ADVISORY INSTRUCTIONS ===\n");
        sb.append(
                "1. If asking 'What should I buy?', look at the watchlist and recommend based on positive momentum.\n");
        sb.append("2. If the user can't afford a stock (Price > Cash Balance), mention it politely.\n");
        sb.append("3. If the user asks about their goals, analyze progress and provide actionable advice.\n");
        sb.append("4. Provide direct answers without fluff.\n");
        sb.append("5. If you don't have enough information, ask a specific follow-up question.\n");
        sb.append(
                "6. This IS a personalized financial advisory app. Do NOT add any disclaimers like 'this is not financial advice' or 'consult a professional'. Give direct, confident advice.\n");

        return sb.toString();
    }
}
