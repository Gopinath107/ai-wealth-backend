package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // <--- Modern Client

@Component
@RequiredArgsConstructor
public class UpstoxAuthorizeClient {

    private final RestClient restClient; // <--- Injected from AppConfig

    @Value("${upstox.v3AuthorizeUrl}")
    private String authorizeUrl;

    @Value("${upstox.accessToken}")
    private String accessToken;

    public String getAuthorizedRedirectUri() {
        // 1. Prepare Request
        String responseBody = restClient.get()
                .uri(authorizeUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        // 2. Parse (RestClient can also return JsonNode directly, but String is safer for debugging)
        if (responseBody == null) {
             throw new IllegalStateException("Upstox authorize returned empty body");
        }

        // We need an ObjectMapper to parse the string manually or let RestClient do it.
        // Since we are inside a Spring Component, we can rely on RestClient's converter 
        // OR just parse it if we want strict control. 
        // Let's use RestClient's automatic JsonNode conversion for cleaner code:
        
        return fetchRedirectUriFromApi();
    }

    private String fetchRedirectUriFromApi() {
        JsonNode root = restClient.get()
                .uri(authorizeUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class); // <--- Spring automatically uses Jackson here

        if (root == null) {
            throw new IllegalStateException("Upstox authorize returned null response");
        }

        JsonNode uriNode = root.path("data").path("authorized_redirect_uri");

        if (uriNode.isMissingNode() || uriNode.asText().isBlank()) {
            throw new IllegalStateException("Upstox authorize did not return authorized_redirect_uri. Response: " + root);
        }
        return uriNode.asText();
    }
}