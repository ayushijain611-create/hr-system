package com.hrapp.leaveservice.client;

import com.hrapp.leaveservice.dto.EvaluationRequestDTO;
import com.hrapp.leaveservice.dto.EvaluationResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

@Component
@Slf4j
public class EvaluationClient {

    private final RestClient restClient;

    public EvaluationClient(
            @Value("${evaluation-service.url}") String evaluationServiceUrl,
            @Value("${evaluation-service.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${evaluation-service.read-timeout-ms:120000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(evaluationServiceUrl)
                .requestFactory(factory)
                .build();
    }

    // Calls the leave-evaluation-service for an AI recommendation; returns empty on any failure so leave creation never blocks on AI.
    public Optional<EvaluationResponseDTO> evaluate(EvaluationRequestDTO request) {
        try {
            log.info("Requesting AI evaluation for leave id={} employeeId={}",
                    request.getLeaveId(), request.getEmployeeId());
            EvaluationResponseDTO response = restClient.post()
                    .uri("/api/evaluations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(EvaluationResponseDTO.class);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("AI evaluation unavailable for leave id={}: {}",
                    request.getLeaveId(), e.getMessage());
            return Optional.empty();
        }
    }
}
