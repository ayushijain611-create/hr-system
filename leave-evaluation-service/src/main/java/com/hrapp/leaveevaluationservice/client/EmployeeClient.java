package com.hrapp.leaveevaluationservice.client;

import com.hrapp.leaveevaluationservice.dto.EmployeeDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Component
@Slf4j
public class EmployeeClient {

    private final RestClient restClient;

    public EmployeeClient(@Value("${employee-service.url}") String employeeServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(employeeServiceUrl)
                .build();
    }

    // Fetches a single employee by id from employee-service; returns empty on any error so callers can degrade gracefully.
    public Optional<EmployeeDTO> getEmployeeById(Long employeeId) {
        try {
            log.debug("Fetching employee id={}", employeeId);
            EmployeeDTO employee = restClient.get()
                    .uri("/api/employees/{id}", employeeId)
                    .retrieve()
                    .body(EmployeeDTO.class);
            return Optional.ofNullable(employee);
        } catch (RestClientException e) {
            log.warn("Employee Service call failed for id {}: {}", employeeId, e.getMessage());
            return Optional.empty();
        }
    }
}
