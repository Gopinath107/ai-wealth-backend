# CLAUDE.md — AI Wealth Advisor Backend

This file provides guidance for AI assistants (Claude and others) working on this codebase.

---

## Project Overview

**Name**: `djai` / AI Wealth Advisor Backend
**Purpose**: Spring Boot REST API backend for an Indian personal finance and investment advisory app. Powers AI-driven chat, real-time market data, investment goal planning, watchlists, and portfolio management.
**Main class**: `com.djai.wealthadvisor.DjaiApplication`
**Base package**: `com.djai.wealthadvisor`

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.1 |
| Build | Maven (use `./mvnw`, not system `mvn`) |
| Database | PostgreSQL — schema `dj` |
| ORM | Spring Data JPA / Hibernate (DDL: `update`) |
| Security | Spring Security + JWT (JJWT 0.12.5) |
| HTTP Client | `RestClient` (Spring 6), `WebClient` (reactive), `OkHttp` |
| Cache | Caffeine (in-memory) |
| Real-time | Spring WebSocket (`/ws/market`) |
| Serialization | Protobuf 3.25.3 (Upstox v3 market feed) |
| Utilities | Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`) |
| AI Providers | Groq API, OpenRouter |
| Container | Docker — multi-stage build (`eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre-alpine`) |

---

## Build & Run

```bash
# Build (skipping tests)
./mvnw clean package -DskipTests

# Run locally
./mvnw spring-boot:run

# Build Docker image
docker build -t ai-wealth-backend .

# Run Docker container
docker run -p 8080:8080 --env-file .env ai-wealth-backend
```

**Note**: Protobuf sources are auto-generated during `compile` phase via `protobuf-maven-plugin`. The `.proto` file lives at `src/main/proto/upstox_market_data_v3.proto`.

---

## Project Structure

```
src/main/java/com/djai/wealthadvisor/
├── DjaiApplication.java           # Main entry point
├── client/                        # External API HTTP clients
│   ├── FinnhubClient.java
│   ├── RapidYahooFinanceClient.java
│   ├── UpstoxAuthorizeClient.java
│   ├── UpstoxClient.java
│   ├── UpstoxHistoryClient.java
│   ├── UpstoxInstrumentFileClient.java
│   ├── UpstoxLtpV3Client.java
│   └── UpstoxMarketFeedV3Client.java
│   └── YahooFinanceClient.java
├── config/                        # Spring configuration beans
│   ├── AppConfig.java
│   ├── CacheConfig.java           # Caffeine cache config
│   ├── CorsConfig.java
│   ├── JwtHandshakeInterceptor.java # JWT auth for WebSocket
│   ├── MailAsyncConfig.java
│   ├── MarketWsConfig.java        # WebSocket endpoint /ws/market
│   ├── MarketWsHandler.java       # WebSocket message handler
│   ├── SecurityConfig.java        # Spring Security + JWT filter chain
│   └── WebClientConfig.java
├── controller/                    # REST controllers
│   ├── AiAdvisorController.java   # POST /api/ai/chat, GET /api/ai/sessions
│   ├── AiStrategyController.java
│   ├── AuthController.java        # POST /api/auth/** (public)
│   ├── BenchmarkController.java
│   ├── GoalController.java
│   ├── InstrumentController.java
│   ├── LivePriceStreamController.java
│   ├── MarketCandleController.java
│   ├── MarketCapController.java
│   ├── MarketDebugController.java
│   ├── MarketFeedController.java
│   ├── MarketIndexController.java
│   ├── MarketMoverController.java
│   ├── MarketNewsController.java
│   ├── MarketQuoteController.java
│   └── WatchlistController.java
├── dto/                           # Request/Response DTOs
├── entity/                        # JPA entities (all in schema "dj")
├── exception/
│   └── GlobalExceptionHandler.java
├── repository/                    # Spring Data JPA repositories
├── security/
│   ├── CustomUserDetailsService.java
│   ├── JwtAuthenticationFilter.java
│   ├── JwtUtil.java
│   └── PasswordHashUtil.java
├── service/                       # Service interfaces
│   └── impl/                     # Service implementations
│       └── InstrumentSyncScheduler.java  # Cron: daily 6:30 AM IST
└── util/
    ├── PasswordHashGenerator.java
    ├── ResponseUtil.java
    └── TechnicalIndicators.java

src/main/proto/
└── upstox_market_data_v3.proto    # Protobuf schema for Upstox v3 feed

src/main/resources/
└── application.properties         # All config (env var overrides)

src/test/
└── DjaiApplicationTests.java      # Basic context load test only
```

---

## Database Schema (`dj`)

All JPA entities map to the `dj` PostgreSQL schema. Hibernate `ddl-auto=update` manages schema changes automatically.

| Table | Entity | Key Fields |
|---|---|---|
| `users` | `User` | `id`, `email` (unique), `password_hash`, `full_name`, `cash_balance`, `is_active` |
| `investment_goal` | `InvestmentGoal` | `id`, `user_id`, `goal_key` (UUID, unique), `name`, `type`, `target_amount`, `current_amount`, `allocation_strategy_json`, `milestones_json`, `contributions_json` |
| `watchlist_item` | `WatchlistItem` | `id`, `user_id`, `instrument_key`, `trading_symbol`, `exchange` — unique on `(user_id, instrument_key)` |
| `instrument_master` | `InstrumentMaster` | PK: `instrument_key`, `trading_symbol`, `name`, `exchange`, `segment`, `instrument_type` |
| `ai_chat_sessions` | `AiChatSession` | `id`, `user_id`, `title`, `created_at`, `updated_at` |
| `ai_chat_messages` | `AiChatMessage` | `id`, `session_id` (FK), `user_id`, `role` (`user`/`assistant`), `content`, `timestamp` |
| `asset` | `Asset` | Portfolio asset holdings |
| `transaction` | `Transaction` | Trade/transaction history |

**Important**: `InstrumentMaster` uses `instrument_key` (String) as the primary key (not auto-generated). Format: `NSE_EQ|RELIANCE`, `BSE_INDEX|SENSEX`, etc.

---

## API Endpoints

All endpoints require JWT `Authorization: Bearer <token>` header **except** `/api/auth/**` and `/ws/**`.

### Authentication
| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login, returns JWT |

### AI Advisor Chat
| Method | Path | Description |
|---|---|---|
| POST | `/api/ai/chat` | Send message, get AI response |
| GET | `/api/ai/sessions/{userId}` | List chat sessions |
| GET | `/api/ai/session/{sessionId}/messages` | Get session messages |

### Investment Goals
| Method | Path | Description |
|---|---|---|
| GET/POST/PUT/DELETE | `/api/goals/**` | CRUD for investment goals |
| POST | `/api/goals/ai/**` | AI goal planning (multi-step) |

### Market Data
| Method | Path | Description |
|---|---|---|
| GET | `/api/market/quote/{instrumentKey}` | Single stock quote |
| GET | `/api/market/candles/{instrumentKey}` | Historical OHLCV candles |
| GET | `/api/market/intraday/{instrumentKey}` | Intraday candles |
| GET | `/api/market/index` | Index data (Nifty 50, Bank Nifty, Sensex) |
| GET | `/api/market/movers` | Top gainers/losers |
| GET | `/api/market/news` | Market news |
| GET | `/api/market/feed` | Market feed snapshot |
| GET | `/api/market/cap` | Market cap data |

### Instruments
| Method | Path | Description |
|---|---|---|
| GET | `/api/instruments/search` | Search NSE/BSE instruments |
| POST | `/api/instruments/sync` | Trigger manual instrument sync |

### Watchlist
| Method | Path | Description |
|---|---|---|
| GET/POST/DELETE | `/api/watchlist/**` | Watchlist CRUD |

### WebSocket
| Endpoint | Description |
|---|---|
| `ws://<host>/ws/market` | Real-time market price streaming. Requires JWT in handshake. |

---

## Security Architecture

- **JWT**: Stateless, token in `Authorization: Bearer` header. Expiry: 24h (86400000ms). Secret via `JWT_SECRET` env var.
- **Password hashing**: **The frontend sends a pre-hashed password**. The backend uses `NoOpPasswordEncoder` and compares the hash strings directly. Do NOT add bcrypt on the backend — this would break authentication.
- **CORS**: All origins permitted (`allowedOriginPatterns = *`). Adjust `SecurityConfig.java` for production lockdown.
- **Public routes**: `/api/auth/**` and `/ws/**` are permit-all. All other routes require a valid JWT.

---

## AI Architecture

### Models (via Groq API)
| Model | Variable | Use Case |
|---|---|---|
| `llama-3.3-70b-versatile` | `api.groq.model.lite` | Primary chat — fast, reliable, used for most responses |
| `groq/compound` | `api.groq.model` | Web-search capable — used ONLY for physical gold/silver/crypto price fallback |
| `xiaomi/mimo-v2-flash:free` | `ai.model.primary` | OpenRouter — used for AI strategy feature |

### Chat Flow (`AiAdvisorServiceImpl`)
1. Extract stock symbols from user message via `extractSearchTerms()`
2. Fetch live prices from Upstox for identified instruments
3. **If instruments found**: inject real-time data into system prompt → call `llama-3.3-70b-versatile`
4. **If price query but no instruments** (physical gold, crypto): call `groq/compound` with web search
5. **Otherwise**: standard chat with `llama-3.3-70b-versatile`
6. Parse and separate `## Follow-ups` section from AI response
7. Save **clean reply** (without follow-ups) to DB, return follow-ups as `List<String>` in response
8. Attach `instrumentKeys` to response for frontend chart rendering

### System Prompt (`AiPromptService`)
Built dynamically per request with:
- AI persona: "DJ-AI" — Indian financial advisor
- User financial snapshot (cash balance, watchlist with live prices, investment goals)
- Response length rules (greeting vs analysis vs simple questions)
- Strict Markdown formatting rules
- Follow-up suggestions requirement
- Advisory rules for Indian market context

### Goal AI (`GoalAiServiceImpl`)
- Two-phase flow: clarification questions (lite model) → full investment plan (compound model)
- Handles Indian goal types: retirement, purchase, real estate, education, etc.
- Retry logic: up to 3 retries on rate limit (429), parses `retry-after` delay from error body

---

## External API Dependencies

| Service | Purpose | Config Key |
|---|---|---|
| Upstox | Primary market data (NSE/BSE quotes, history, indices, live feed) | `UPSTOX_ACCESS_TOKEN`, `UPSTOX_CLIENT_ID`, `UPSTOX_CLIENT_SECRET` |
| Groq | AI chat (llama + compound) | `GROQ_API_KEY` |
| OpenRouter | AI strategy (mimo-v2-flash) | `OPENROUTER_API_KEY` |
| Marketaux | Market news | `MARKETAUX_API_KEY` |
| Twelve Data | Financial time-series | `TWELVEDATA_API_KEY` |
| Finnhub | Market data / news | `FINNHUB_API_KEY` |
| Alpha Vantage | Financial data | `ALPHAVANTAGE_API_KEY` |
| Yahoo Finance (RapidAPI) | Market data | `RAPIDAPI_KEY` |
| Tavily | AI web search | `TAVILY_API_KEY` |

---

## Scheduled Tasks

| Scheduler | Cron | Description |
|---|---|---|
| `InstrumentSyncScheduler` | `0 30 6 * * *` (6:30 AM IST) | Downloads and upserts full NSE/BSE instrument master from Upstox GZ file |

The instrument sync processes a compressed JSON file from Upstox assets URL into the `instrument_master` table. Run manually via `POST /api/instruments/sync`.

---

## Environment Variables

Required for production. Local defaults exist in `application.properties`.

```
# Database
DB_URL=jdbc:postgresql://<host>:5432/<db>?currentSchema=dj
DB_USERNAME=<user>
DB_PASSWORD=<pass>

# Security
JWT_SECRET=<min 256-bit string>

# Upstox
UPSTOX_ACCESS_TOKEN=<token>
UPSTOX_CLIENT_ID=<id>
UPSTOX_CLIENT_SECRET=<secret>
UPSTOX_REDIRECT_URL=<callback URL>

# AI
GROQ_API_KEY=<key>
OPENROUTER_API_KEY=<key>

# Market Data
MARKETAUX_API_KEY=<key>
TWELVEDATA_API_KEY=<key>
FINNHUB_API_KEY=<key>
ALPHAVANTAGE_API_KEY=<key>
RAPIDAPI_KEY=<key>

# Search
TAVILY_API_KEY=<key>
```

**Local dev defaults** (no env vars needed for basic startup):
- DB: `localhost:5432/wealth` (schema `dj`), user/pass `dj/dj`
- JWT secret: hardcoded fallback in `application.properties`
- Port: `8080`

---

## Key Conventions

### Code Style
- All services use **interface + implementation** pattern (`GoalService` / `GoalServiceImpl`)
- Exception: `AiAdvisorServiceImpl` has no interface (direct injection in controller)
- Lombok `@Data` on all entities and most DTOs
- `@Slf4j` + `@RequiredArgsConstructor` on service implementations
- Use `@Value("${property.key}")` for injecting config into services

### Entity Conventions
- All entities belong to schema `dj` — always add `schema = "dj"` in `@Table`
- Use `@PrePersist` / `@PreUpdate` for `createdAt` / `updatedAt` timestamps
- IDs are `Long` with `GenerationType.IDENTITY`, except `InstrumentMaster` which uses a String PK

### DTOs
- Separate request (`*RequestDto`, `*Dto`) and response (`*ResponseDto`, `*Dto`) objects
- Use `AdvisorRequestDto` / `AdvisorResponseDto` for AI chat endpoints
- `AdvisorResponseDto` contains: `reply`, `followUps` (List), `instrumentKeys` (for charts), `sessionId`, `status`, `timestamp`

### AI Response Handling
- Always strip `## Follow-ups` section before saving to DB (prevents duplicate rendering)
- The `findFollowUpIndex()` method handles many Unicode dash variants (`-`, `‑`, `–`, `—`) — do not simplify it
- `foundInstrumentKeys` is a `ThreadLocal<List<String>>` — always call `.remove()` after use

### Upstox Instrument Keys
- Format: `{EXCHANGE}_{SEGMENT}|{SYMBOL}` — e.g., `NSE_EQ|RELIANCE`, `NSE_INDEX|Nifty 50`, `BSE_EQ|TATAMOTORS`
- Pipe `|` characters in URLs are supported via `server.tomcat.relaxed-path-chars=|`

### Indian Market Context
- All monetary values use INR (`₹`)
- Market hours: NSE/BSE 9:15 AM – 3:30 PM IST (weekdays)
- Indices: Nifty 50 (`NSE_INDEX|Nifty 50`), Nifty Bank (`NSE_INDEX|Nifty Bank`), Sensex (`BSE_INDEX|SENSEX`)
- The `market.universe.symbols` property defines the ~46 default Nifty 50 stocks

---

## Testing

Only a single context-load test exists:

```bash
./mvnw test
```

`DjaiApplicationTests` requires a running database and all environment variables to be set. For CI, either mock the DB or use `@SpringBootTest` with a test profile.

---

## Development Workflow

### Running Locally
1. Start PostgreSQL locally (`localhost:5432`, database `wealth`, schema `dj`, user `dj`)
2. Set required env vars (at minimum: `GROQ_API_KEY`, `UPSTOX_ACCESS_TOKEN`)
3. `./mvnw spring-boot:run`

### Making Changes
- **New entity**: Add `@Table(schema = "dj")`, run app — Hibernate `ddl-auto=update` creates the table
- **New endpoint**: Add controller method, update `SecurityConfig` if route needs to be public
- **New AI prompt tweak**: Modify `AiPromptService.buildSystemPrompt()` or the relevant prompt method in `GoalAiServiceImpl`
- **New external API**: Add a client class in `client/`, configure URL/key in `application.properties`, wire via `@Value`

### Git Branch
- Active dev branch: `claude/claude-md-mm8yajix7tauv6jl-WdIHr`
- Main branch: `master`

---

## Common Pitfalls

1. **NoOpPasswordEncoder is intentional** — Frontend sends SHA-256/bcrypt hash, backend compares as plain string. Do not replace with `BCryptPasswordEncoder`.

2. **Groq compound model crashes with large system prompts** — Use `groqModelLite` (`llama-3.3-70b-versatile`) for standard chat. Only use `groqModel` (`groq/compound`) for price fallback with a minimal prompt.

3. **Upstox rate limits** — Quote API calls are rate-limited. The `callGroqApiWithRetry()` handles Groq 429s; Upstox errors are caught and logged per-instrument without failing the whole request.

4. **ThreadLocal cleanup** — `foundInstrumentKeys.remove()` must be called after `processChat()` completes to prevent memory leaks in thread pools.

5. **Follow-up deduplication** — The AI is instructed to generate `## Follow-ups` sections. These are stripped before DB storage and returned separately. If the AI changes its heading format, update `findFollowUpIndex()` regex pattern.

6. **Instrument sync requires disk space** — The Upstox instrument file is a large GZ. Ensure the deployment environment has adequate temp storage.

7. **DDL auto-update in production** — `spring.jpa.hibernate.ddl-auto=update` runs on startup. This is safe for adding columns but can fail on destructive schema changes. Be careful with entity modifications.
