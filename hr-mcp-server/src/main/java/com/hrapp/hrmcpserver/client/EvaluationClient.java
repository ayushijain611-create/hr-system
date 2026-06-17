package com.hrapp.hrmcpserver.client;

import com.hrapp.hrmcpserver.config.RestClientsConfig;
import com.hrapp.hrmcpserver.dto.EvaluationRequestDto;
import com.hrapp.hrmcpserver.dto.EvaluationResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP facade over leave-evaluation-service. Single endpoint:
 * `POST /api/evaluations` returns the RAG-backed recommendation.
 *
 * Errors are intentionally NOT swallowed here — the tools layer turns
 * exceptions into LLM-readable error strings. That way the model can
 * differentiate "service unavailable" from "approved with low confidence",
 * which matters for the conversational UX.
 */
@Component
@Slf4j
public class EvaluationClient {

    private final RestClient restClient;

    public EvaluationClient(@Qualifier(RestClientsConfig.EVALUATION_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    public EvaluationResponseDto evaluate(EvaluationRequestDto request) {
        log.debug("Requesting evaluation for employeeId={} type={} {}-{}",
                request.getEmployeeId(), request.getLeaveType(),
                request.getStartDate(), request.getEndDate());
        return restClient.post()
                .uri("/api/evaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EvaluationResponseDto.class);
    }
}
