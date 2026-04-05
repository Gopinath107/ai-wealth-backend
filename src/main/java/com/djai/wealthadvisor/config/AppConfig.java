package com.djai.wealthadvisor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AppConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        // SimpleClientHttpRequestFactory uses standard JDK HttpClient (robust DNS).
        // Read timeout is 90s — Groq's compound-beta (web search) model can take 45-90s.
        // Connection timeout is kept short (15s) to fail fast on network issues.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(90));

        return builder
                .requestFactory(factory)
                .build();
    }
}