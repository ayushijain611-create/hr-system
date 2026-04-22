package com.hrapp.leaveservice.client;

import com.hrapp.leaveservice.dto.EmployeeDTO;
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

    public EmployeeClient(
            @Value("${employee-service.url}") String employeeServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(employeeServiceUrl)
                .build();
    }

    public Optional<EmployeeDTO> getEmployeeById(Long employeeId) {
        try {
            log.info("Calling Employee Service for employee id: {}",
                    employeeId);
            EmployeeDTO employee = restClient.get()
                    .uri("/api/employees/{id}", employeeId)
                    .retrieve()
                    .body(EmployeeDTO.class);
            return Optional.ofNullable(employee);
        } catch (RestClientException e) {
            log.error("Employee Service call failed for id {}: {}",
                    employeeId, e.getMessage());
            return Optional.empty();
        }
    }
}