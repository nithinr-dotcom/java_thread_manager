package com.fleet.storefront.adapter.out.hq;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The one HTTP client to Central HQ. Always bounded by connect and read timeouts: a hung WAN link must
 * fail fast so the relay can back off, instead of pinning a relay thread forever.
 */
@Component
@ConditionalOnExpression("!'${storefront.hq.base-url:}'.isBlank()")
class HqHttpClient {

    private final RestClient http;

    HqHttpClient(RestClient.Builder builder,
                 @Value("${storefront.hq.base-url}") String baseUrl,
                 @Value("${storefront.hq.api-key:}") String apiKey,
                 @Value("${storefront.branch.node-id}") String nodeId,
                 @Value("${storefront.hq.connect-timeout:2s}") Duration connectTimeout,
                 @Value("${storefront.hq.read-timeout:5s}") Duration readTimeout) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(readTimeout);
        RestClient.Builder configured = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Origin-Node", nodeId);
        if (!apiKey.isBlank()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.http = configured.build();
    }

    RestClient http() {
        return http;
    }
}
