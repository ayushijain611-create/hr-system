package com.hrapp.leaveevaluationservice.client;

import com.hrapp.leaveevaluationservice.dto.LeaveSummaryDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class LeaveHistoryClient {

    private final RestClient restClient;

    public LeaveHistoryClient(@Value("${leave-service.url}") String leaveServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(leaveServiceUrl)
                .build();
    }

    // Returns all historical leaves for an employee; empty list on any error.
    public List<LeaveSummaryDTO> getLeavesForEmployee(Long employeeId) {
        try {
            LeaveSummaryDTO[] result = restClient.get()
                    .uri("/api/leaves/employee/{id}", employeeId)
                    .retrieve()
                    .body(LeaveSummaryDTO[].class);
            return result == null ? List.of() : Arrays.asList(result);
        } catch (RestClientException e) {
            log.warn("Leave history call failed for employee {}: {}", employeeId, e.getMessage());
            return List.of();
        }
    }

    // Finds APPROVED leaves overlapping the given date window (excluding the requesting employee).
    // Returns empty list if the search endpoint is unavailable (e.g. before leave-service is updated).
    public List<LeaveSummaryDTO> searchOverlappingApproved(
            LocalDate startDate, LocalDate endDate, Long excludeEmployeeId) {
        try {
            LeaveSummaryDTO[] result = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/leaves/search")
                            .queryParam("startDate", startDate)
                            .queryParam("endDate", endDate)
                            .queryParam("status", "APPROVED")
                            .queryParam("excludeEmployeeId", excludeEmployeeId)
                            .build())
                    .retrieve()
                    .body(LeaveSummaryDTO[].class);
            return result == null ? List.of() : Arrays.asList(result);
        } catch (RestClientException e) {
            log.warn("Overlap search unavailable ({}); returning empty list", e.getMessage());
            return List.of();
        }
    }
}
