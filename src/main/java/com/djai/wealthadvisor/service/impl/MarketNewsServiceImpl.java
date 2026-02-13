package com.djai.wealthadvisor.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.djai.wealthadvisor.dto.MarketNewsDto;
import com.djai.wealthadvisor.service.MarketNewsService;
import com.djai.wealthadvisor.service.NewsAiImpactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class MarketNewsServiceImpl implements MarketNewsService {

	private final WebClient webClient;
	private final ObjectMapper objectMapper;

	private final NewsAiImpactService aiImpactService;

	@Value("${api.marketaux.key}")
	private String marketauxKey;

	@Value("${api.marketaux.url}")
	private String marketauxBaseUrl;

	@Value("${api.tavily.key}")
	private String tavilyKey;

	@Value("${api.tavily.url}")
	private String tavilyUrl;

	// Tune these if needed
	private static final int MAX_LIMIT = 50;
	private static final int MARKET_AUX_MAX_PAGES = 3; // keeps API usage sane (100/day)
	private static final int MARKET_AUX_PER_PAGE_REQ = 20; // API may cap anyway; we paginate if needed
	private static final int AI_BATCH_SIZE = 10;
	private static final String DISCLAIMER = "This analysis is for informational purposes only and does not constitute financial advice.";

	// Cache: reduce MarketAux calls + reduce AI calls
	private final Cache<String, List<MarketNewsDto>> feedCache = Caffeine.newBuilder()
			.expireAfterWrite(120, TimeUnit.SECONDS).maximumSize(200).build();

	private final Cache<String, List<MarketNewsDto>> searchCache = Caffeine.newBuilder()
			.expireAfterWrite(120, TimeUnit.SECONDS).maximumSize(500).build();

	// Cache AI impact by URL (stable)
	private final Cache<String, MarketNewsDto> impactCache = Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS)
			.maximumSize(5000).build();

	@Override
	public List<MarketNewsDto> feed(String tab, Integer limit, Integer page) {
		int lim = clamp(limit, 1, MAX_LIMIT);
		int pg = clamp(page, 1, 1000);

		String cacheKey = "feed:" + tab + ":" + lim + ":" + pg;
		return feedCache.get(cacheKey, k -> computeFeed(tab, lim, pg));
	}

	@Override
	public List<MarketNewsDto> search(String q, Integer limit) {
		String query = (q == null) ? "" : q.trim();
		if (query.isBlank())
			throw new IllegalArgumentException("q is required");

		int lim = clamp(limit, 1, MAX_LIMIT);
		String cacheKey = "search:" + query.toLowerCase(Locale.ROOT) + ":" + lim;

		return searchCache.get(cacheKey, k -> computeSearch(query, lim));
	}

	private List<MarketNewsDto> computeFeed(String tab, int limit, int page) {

		// Fetch in parallel (FAST)
		Mono<List<MarketNewsDto>> indiaMono = Mono.fromCallable(() -> safeMarketauxFeed(limit, page, "in", null));
		Mono<List<MarketNewsDto>> globalMono = Mono.fromCallable(() -> safeMarketauxFeed(limit, 1, null, null));
		Mono<List<MarketNewsDto>> tavilyMono = Mono
				.fromCallable(() -> safeTavilySearch("India stock market news NSE BSE Nifty Sensex RBI", limit));

		List<MarketNewsDto> india = indiaMono.onErrorReturn(List.of()).block();
		List<MarketNewsDto> global = globalMono.onErrorReturn(List.of()).block();
		List<MarketNewsDto> tav = tavilyMono.onErrorReturn(List.of()).block();

		// Merge (India first)
		List<MarketNewsDto> merged = new ArrayList<>();
		addUniqueByUrl(merged, india);
		addUniqueByUrl(merged, global);
		addUniqueByUrl(merged, tav);

		// Trim early for AI speed (we only enrich what we return)
		if (merged.size() > limit)
			merged = merged.subList(0, limit);

		// AI enrich (single call)
		enrichAiImpact(merged);

		// Apply tab filter using AI labels (better UX)
		List<MarketNewsDto> filtered = applyTabFilterUsingAi(merged, tab);

		if (filtered.size() > limit)
			filtered = filtered.subList(0, limit);
		return filtered;
	}

	private List<MarketNewsDto> computeSearch(String q, int limit) {

		Mono<List<MarketNewsDto>> marketauxMono = Mono.fromCallable(() -> safeMarketauxSearch(q, limit, "in"));
		Mono<List<MarketNewsDto>> tavilyMono = Mono.fromCallable(() -> safeTavilySearch(q, limit));

		List<MarketNewsDto> m = marketauxMono.onErrorReturn(List.of()).block();
		List<MarketNewsDto> t = tavilyMono.onErrorReturn(List.of()).block();

		List<MarketNewsDto> merged = new ArrayList<>();
		addUniqueByUrl(merged, m);
		addUniqueByUrl(merged, t);

		if (merged.size() > limit)
			merged = merged.subList(0, limit);

		enrichAiImpact(merged);

		return merged;
	}

	private List<MarketNewsDto> safeMarketauxFeed(int limit, int page, String countries, String search) {
		try {
			return marketauxFetch(limit, page, countries, search);
		} catch (Exception e) {
			return List.of();
		}
	}

	private List<MarketNewsDto> safeMarketauxSearch(String q, int limit, String countries) {
		try {
			// page=1 for search; if you want more, you can paginate later
			return marketauxFetch(limit, 1, countries, q);
		} catch (Exception e) {
			return List.of();
		}
	}

	private List<MarketNewsDto> marketauxFetch(int limit, int page, String countries, String search) throws Exception {

		int perPage = Math.min(limit, MARKET_AUX_PER_PAGE_REQ);

		String uri = marketauxBaseUrl + "/news/all";

		Map<String, String> qp = new LinkedHashMap<>();
		qp.put("api_token", marketauxKey);
		qp.put("limit", String.valueOf(perPage));
		qp.put("page", String.valueOf(page));
		qp.put("language", "en");
		qp.put("filter_entities", "true");
		qp.put("must_have_entities", "true");
		qp.put("group_similar", "true");

		if (countries != null && !countries.isBlank())
			qp.put("countries", countries);
		if (search != null && !search.isBlank())
			qp.put("search", search);

		String fullUrl = uri + "?" + qp.entrySet().stream().map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
				.collect(Collectors.joining("&"));

		String json = webClient.get().uri(fullUrl).accept(MediaType.APPLICATION_JSON).retrieve()
				.bodyToMono(String.class).timeout(Duration.ofSeconds(10)) // shorter = faster failover
				.block();

		if (json == null || json.isBlank())
			return List.of();

		JsonNode root = objectMapper.readTree(json);
		JsonNode data = root.path("data");
		if (!data.isArray() || data.size() == 0)
			return List.of();

		List<MarketNewsDto> out = new ArrayList<>();
		Set<String> seenUrls = new HashSet<>();

		for (JsonNode n : data) {
			MarketNewsDto dto = new MarketNewsDto();
			dto.setProvider("marketaux");
			dto.setNewsId(text(n, "uuid"));
			dto.setTitle(cleanTitle(text(n, "title")));
			dto.setDescription(cleanDescription(text(n, "description")));
			dto.setUrl(text(n, "url"));
			dto.setSource(text(n, "source"));
			dto.setImageUrl(text(n, "image_url"));
			dto.setPublishedAt(text(n, "published_at"));

			Double s = n.hasNonNull("sentiment_score") ? n.get("sentiment_score").asDouble() : 0.0;
			dto.setSentimentScore(s);
			dto.setSentimentLabel(scoreToLabel(s));
			dto.setDisclaimer(DISCLAIMER);

			if (dto.getUrl() != null && !dto.getUrl().isBlank() && seenUrls.add(dto.getUrl())) {
				out.add(dto);
			}
			if (out.size() >= limit)
				break;
		}

		return out;
	}

	private List<MarketNewsDto> safeTavilySearch(String q, int limit) {
		try {
			return tavilySearch(q, limit);
		} catch (Exception e) {
			return List.of();
		}
	}

	private List<MarketNewsDto> tavilySearch(String q, int limit) throws Exception {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("api_key", tavilyKey);
		body.put("query", q);
		body.put("search_depth", "basic");
		body.put("max_results", limit);
		body.put("include_answer", false);
		body.put("include_images", false);
		body.put("include_raw_content", false);

		String json = webClient.post().uri(tavilyUrl).contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(String.class)
				.timeout(Duration.ofSeconds(20)).block();

		if (json == null || json.isBlank())
			return List.of();

		JsonNode root = objectMapper.readTree(json);
		JsonNode results = root.path("results");
		if (!results.isArray())
			return List.of();

		List<MarketNewsDto> out = new ArrayList<>();
		for (JsonNode r : results) {
			MarketNewsDto dto = new MarketNewsDto();
			dto.setProvider("tavily");

			dto.setTitle(cleanTitle(r.path("title").asText(null)));
			dto.setUrl(r.path("url").asText(null));
			dto.setDescription(cleanDescription(r.path("content").asText(null)));

			String url = dto.getUrl();
			dto.setNewsId("t_" + shortHash(url != null ? url : dto.getTitle()));
			dto.setSource(extractDomain(url));

			dto.setSentimentScore(0.0);
			dto.setSentimentLabel("Neutral");
			dto.setDisclaimer(DISCLAIMER);

			if (dto.getUrl() != null && !dto.getUrl().isBlank())
				out.add(dto);
		}

		return out;
	}

	// ---------------- AI enrichment (batch) ----------------

	private void enrichAiImpact(List<MarketNewsDto> items) {

		// Only enrich missing
		List<MarketNewsDto> needs = items.stream().filter(n -> n.getUrl() != null && !n.getUrl().isBlank())
				.filter(n -> n.getAiImpactSummary() == null || n.getAiImpactLabel() == null)
				.collect(Collectors.toList());

		if (needs.isEmpty()) {
			// mark cached = true for already having AI fields
			items.forEach(n -> n.setImpactCached(n.getAiImpactSummary() != null));
			return;
		}

		// Apply cached impacts first
		Iterator<MarketNewsDto> it = needs.iterator();
		while (it.hasNext()) {
			MarketNewsDto n = it.next();
			MarketNewsDto cached = impactCache.getIfPresent(n.getUrl());
			if (cached != null && cached.getAiImpactSummary() != null) {
				copyImpact(cached, n);
				n.setImpactCached(true);
				it.remove();
			}
		}

		// Batch call AI for the rest
		for (int i = 0; i < needs.size(); i += AI_BATCH_SIZE) {
			List<MarketNewsDto> batch = needs.subList(i, Math.min(i + AI_BATCH_SIZE, needs.size()));
			List<NewsAiImpactService.AiImpact> impacts = aiImpactService.analyzeBatch(batch);

			// map impacts by url
			Map<String, NewsAiImpactService.AiImpact> map = impacts.stream().filter(x -> x.url() != null)
					.collect(Collectors.toMap(NewsAiImpactService.AiImpact::url, x -> x, (a, b) -> a));

			for (MarketNewsDto n : batch) {
				NewsAiImpactService.AiImpact imp = map.get(n.getUrl());
				if (imp == null) {
					// fallback impact
					String fallbackLabel = (n.getSentimentLabel() != null) ? n.getSentimentLabel() : "Neutral";
					double fallbackScore = (n.getSentimentScore() != null) ? n.getSentimentScore() : 0.0;

					n.setAiImpactLabel(fallbackLabel);
					n.setAiImpactScore(clampScore(fallbackScore));
					n.setAiImpactSummary(
							"AI impact analysis is temporarily unavailable; using provider sentiment as fallback.");
					n.setAiKeyPoints(
							"• Provider sentiment used as fallback\n• Try again later for AI summary\n• Not financial advice");
					n.setImpactCached(false);

				} else {
					n.setAiImpactLabel(imp.label());
					n.setAiImpactScore(imp.score());
					n.setAiImpactSummary(imp.summary());
					n.setAiKeyPoints(String.join("\n", imp.keyPoints().stream().map(k -> "• " + k).toList()));
					n.setImpactCached(false);

					// store in cache
					MarketNewsDto cached = new MarketNewsDto();
					cached.setUrl(n.getUrl());
					cached.setAiImpactLabel(n.getAiImpactLabel());
					cached.setAiImpactScore(n.getAiImpactScore());
					cached.setAiImpactSummary(n.getAiImpactSummary());
					cached.setAiKeyPoints(n.getAiKeyPoints());
					impactCache.put(n.getUrl(), cached);
				}
			}
		}

		// Ensure everyone has the flag consistent
		items.forEach(n -> {
			if (n.getImpactCached() == null)
				n.setImpactCached(n.getAiImpactSummary() != null);
		});
	}

	private static double clampScore(double s) {
		if (s > 1.0)
			return 1.0;
		if (s < -1.0)
			return -1.0;
		return s;
	}

	private static void copyImpact(MarketNewsDto from, MarketNewsDto to) {
		to.setAiImpactLabel(from.getAiImpactLabel());
		to.setAiImpactScore(from.getAiImpactScore());
		to.setAiImpactSummary(from.getAiImpactSummary());
		to.setAiKeyPoints(from.getAiKeyPoints());
	}

	// ---------------- filtering + helpers ----------------

	private static List<MarketNewsDto> applyTabFilter(List<MarketNewsDto> items, String tab) {
		if (tab == null || tab.equalsIgnoreCase("all"))
			return items;

		String t = tab.trim().toLowerCase(Locale.ROOT);
		return items.stream().filter(n -> {
			String label = n.getSentimentLabel();
			if (label == null)
				label = "Neutral";
			return label.equalsIgnoreCase(t);
		}).collect(Collectors.toList());
	}

	private static List<MarketNewsDto> applyTabFilterUsingAi(List<MarketNewsDto> items, String tab) {
		if (tab == null || tab.equalsIgnoreCase("all"))
			return items;

		String t = tab.trim().toLowerCase(Locale.ROOT);
		return items.stream().filter(n -> {
			String label = n.getAiImpactLabel();
			if (label == null)
				label = n.getSentimentLabel();
			if (label == null)
				label = "Neutral";
			return label.equalsIgnoreCase(t);
		}).collect(Collectors.toList());
	}

	private static void addUniqueByUrl(List<MarketNewsDto> base, List<MarketNewsDto> add) {
		Set<String> urls = base.stream().map(MarketNewsDto::getUrl).filter(Objects::nonNull)
				.collect(Collectors.toSet());

		for (MarketNewsDto n : add) {
			if (n.getUrl() != null && urls.add(n.getUrl()))
				base.add(n);
		}
	}

	private static String scoreToLabel(double score) {
		if (score > 0.15)
			return "Bullish";
		if (score < -0.15)
			return "Bearish";
		return "Neutral";
	}

	private static int clamp(Integer v, int min, int max) {
		if (v == null)
			return min;
		return Math.max(min, Math.min(max, v));
	}

	private static String text(JsonNode n, String field) {
		JsonNode x = n.get(field);
		return (x == null || x.isNull()) ? null : x.asText();
	}

	private static String cleanTitle(String s) {
		if (s == null)
			return null;
		return s.replaceAll("\\s+", " ").trim();
	}

	private static String cleanDescription(String s) {
		if (s == null)
			return null;

		String out = s;

		// remove markdown + noisy patterns
		out = out.replaceAll(":max_bytes\\(\\d+\\):strip_icc\\(\\)/", "");
		out = out.replaceAll("\\[!\\[.*?\\]\\(.*?\\)\\]\\(.*?\\)", ""); // image markdown
		out = out.replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1"); // link markdown
		out = out.replaceAll("utm_source=[^\\s)]+", "");
		out = out.replaceAll("\\s+", " ").trim();

		// stop massive menu dumps
		if (out.length() > 400)
			out = out.substring(0, 400).trim() + "...";

		// remove leading #
		out = out.replaceFirst("^#\\s*", "");

		return out;
	}

	private static String extractDomain(String url) {
		try {
			if (url == null)
				return null;
			String u = url.replace("https://", "").replace("http://", "");
			int slash = u.indexOf('/');
			return (slash > 0) ? u.substring(0, slash) : u;
		} catch (Exception e) {
			return null;
		}
	}

	private static String shortHash(String input) {
		try {
			if (input == null)
				input = UUID.randomUUID().toString();
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++)
				sb.append(String.format("%02x", digest[i]));
			return sb.toString();
		} catch (Exception e) {
			return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		}
	}

	private static String urlEncode(String v) {
		return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
	}
}
