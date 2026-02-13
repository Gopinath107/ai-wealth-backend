package com.djai.wealthadvisor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AppConfig { // Or RestClientConfig

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        // SimpleClientHttpRequestFactory uses standard JDK HttpClient (robust DNS)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));

        return builder
                .requestFactory(factory)
                .build();
    }
}