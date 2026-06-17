package com.hrapp.hrmcpserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Produces one named {@link RestClient} per upstream HR microservice.
 *
 * Centralizing here keeps timeouts and any future cross-cutting concerns
 * (auth headers, request/response logging interceptors, retry policies)
 * in a single place. Tool-facing clients inject the appropriate
 * `RestClient` by qualifier.
 */
@Configuration
public class RestClientsConfig {

    public static final String EMPLOYEE_REST_CLIENT = "employeeRestClient";
    public static final String LEAVE_REST_CLIENT = "leaveRestClient";
    public static final String EVALUATION_REST_CLIENT = "evaluationRestClient";

    @Bean(EMPLOYEE_REST_CLIENT)
    RestClient employeeRestClient(
            @Value("${employee-service.url}") String baseUrl,
            @Value("${employee-service.connect-timeout-ms:5000}") int connectMs,
            @Value("${employee-service.read-timeout-ms:15000}") int readMs) {
        return build(baseUrl, connectMs, readMs);
    }

    @Bean(LEAVE_REST_CLIENT)
    RestClient leaveRestClient(
            @Value("${leave-service.url}") String baseUrl,
            @Value("${leave-service.connect-timeout-ms:5000}") int connectMs,
            @Value("${leave-service.read-timeout-ms:15000}") int readMs) {
        return build(baseUrl, connectMs, readMs);
    }

    @Bean(EVALUATION_REST_CLIENT)
    RestClient evaluationRestClient(
            @Value("${evaluation-service.url}") String baseUrl,
            @Value("${evaluation-service.connect-timeout-ms:5000}") int connectMs,
            // LLM-backed evaluation can take 30s+ on cold model load; give it room.
            @Value("${evaluation-service.read-timeout-ms:120000}") int readMs) {
        return build(baseUrl, connectMs, readMs);
    }

    private static RestClient build(String baseUrl, int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
