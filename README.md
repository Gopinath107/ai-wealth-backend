# DJ-AI Wealth Advisor — Backend

A Spring Boot REST API powering the AI Wealth Advisor platform. Provides portfolio management, real-time market data, AI-driven financial analysis, and multi-provider authentication.

## ✨ Features

- **User Authentication** — Email/password registration, login, and social auth (Google, GitHub via Supabase)
- **Portfolio Management** — Buy/sell stocks, track holdings, and calculate P&L
- **Real-Time Market Data** — Live prices via Upstox WebSocket feed
- **AI Financial Advisor** — Multi-model AI chat with portfolio-aware context (Groq, OpenRouter)
- **News Feed** — Market news aggregation with AI-powered impact analysis
- **Goal Planner** — AI-generated investment plans based on user goals
- **Watchlist** — Personalized stock watchlist with alerts
- **Instrument Database** — Auto-synced NSE/BSE instrument master data

## 🛠 Tech Stack

| Technology | Purpose |
|---|---|
| Java 17 + Spring Boot 3 | Application framework |
| Spring Security + JWT | Authentication & authorization |
| Spring Data JPA + Hibernate | Data persistence |
| PostgreSQL | Database |
| Upstox API v2/v3 | Real-time market data & WebSocket feeds |
| Groq / OpenRouter | AI model providers |
| Tavily | Web search for AI agent |
| Supabase Auth | Social login token verification |

## 🏗 Architecture

```
┌──────────────────────────────────────────────────┐
│                   Controllers                     │
│  AuthController · PortfolioController · etc.      │
├──────────────────────────────────────────────────┤
│                    Services                       │
│  AuthService · AIService · MarketService · etc.   │
├──────────────────────────────────────────────────┤
│                   Security                        │
│  JwtUtil · JwtAuthFilter · SecurityConfig         │
├──────────────────────────────────────────────────┤
│               Repositories (JPA)                  │
│  UserRepo · PortfolioRepo · WatchlistRepo · etc.  │
├──────────────────────────────────────────────────┤
│               PostgreSQL Database                 │
│              Schema: dj                           │
└──────────────────────────────────────────────────┘
```

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven
- PostgreSQL (local or cloud)

### Installation

```bash
# Clone the repository
git clone https://github.com/Gopinath107/ai-wealth-backend.git
cd ai-wealth-backend

# Build the project
./mvnw clean install -DskipTests

# Run
./mvnw spring-boot:run
```

The server starts on [http://localhost:8080](http://localhost:8080).

### Environment Variables

| Variable | Description | Required |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC URL | Yes |
| `DB_USERNAME` | Database username | Yes |
| `DB_PASSWORD` | Database password | Yes |
| `JWT_SECRET` | Secret key for system JWT signing (min 256-bit) | Yes |
| `SUPABASE_JWT_SECRET` | Supabase project JWT secret (for social auth) | Yes |
| `GROQ_API_KEY` | Groq API key for AI models | Yes |
| `OPENROUTER_API_KEY` | OpenRouter API key | Yes |
| `TAVILY_API_KEY` | Tavily search API key | Yes |
| `UPSTOX_ACCESS_TOKEN` | Upstox API access token | Yes |
| `UPSTOX_CLIENT_ID` | Upstox OAuth client ID | Yes |
| `UPSTOX_CLIENT_SECRET` | Upstox OAuth client secret | Yes |
| `UPSTOX_REDIRECT_URL` | Upstox OAuth callback URL | Yes |
| `MARKETAUX_API_KEY` | MarketAux news API key | Optional |
| `TWELVEDATA_API_KEY` | Twelve Data API key | Optional |
| `FINNHUB_API_KEY` | Finnhub API key | Optional |
| `ALPHAVANTAGE_API_KEY` | Alpha Vantage API key | Optional |
| `RAPIDAPI_KEY` | RapidAPI key (Yahoo Finance) | Optional |

## 🔑 API Endpoints

### Authentication

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Register new user |
| `POST` | `/api/auth/login` | Login with email/password |
| `POST` | `/api/auth/social-login` | Social login via Supabase token |
| `POST` | `/api/auth/change-password` | Change user password |

### Portfolio

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/portfolio` | Get user portfolio |
| `POST` | `/api/portfolio/buy` | Buy stocks |
| `POST` | `/api/portfolio/sell` | Sell stocks |
| `GET` | `/api/portfolio/history` | Transaction history |

### Market Data

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/market/quotes` | Get stock quotes |
| `GET` | `/api/market/indices` | Get market indices |
| `GET` | `/api/market/search` | Search instruments |

### AI Advisor

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/advisor/chat` | Chat with AI advisor |
| `GET` | `/api/advisor/history` | Get chat history |

## 🔐 Authentication Flow

### Manual Login
```
POST /api/auth/login { email, passwordHash }
  → Validates credentials
  → Returns system JWT + user info
```

### Social Login (Google / GitHub)
```
POST /api/auth/social-login { provider, supabaseToken }
  → Verifies Supabase JWT using SUPABASE_JWT_SECRET
  → Extracts email/name from token claims
  → Finds existing user or auto-creates new one
  → Returns system JWT + user info
```

## 📁 Project Structure

```
src/main/java/com/djai/wealthadvisor/
├── DjaiApplication.java           # Spring Boot entry point
├── config/
│   └── SecurityConfig.java         # Spring Security + CORS
├── security/
│   ├── JwtUtil.java                # JWT generation & validation
│   └── JwtAuthFilter.java         # JWT authentication filter
├── controller/
│   ├── AuthController.java         # Auth endpoints (manual + social)
│   ├── PortfolioController.java    # Portfolio CRUD
│   ├── MarketController.java       # Market data
│   ├── AdvisorController.java      # AI chat
│   └── ...
├── service/
│   ├── AuthService.java            # Auth interface
│   └── impl/
│       └── AuthServiceImpl.java    # Auth implementation
├── dto/
│   └── AuthDto.java                # Request/Response DTOs
├── entity/
│   ├── User.java                   # User entity
│   ├── Portfolio.java              # Portfolio entity
│   └── ...
├── repository/
│   ├── UserRepository.java
│   └── ...
├── client/                         # External API clients
├── exception/                      # Custom exceptions
└── util/                           # Utility classes

src/main/resources/
└── application.properties          # Configuration
```

## 🌐 Deployment

Deployed on **Render** as a Web Service.

- **Build Command**: `./mvnw clean install -DskipTests`
- **Start Command**: `java -jar target/*.jar`
- Set all environment variables in Render dashboard

## 📄 License

This project is for educational and personal use.
