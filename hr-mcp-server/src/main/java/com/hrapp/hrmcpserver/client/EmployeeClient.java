package com.hrapp.hrmcpserver.client;

import com.hrapp.hrmcpserver.config.RestClientsConfig;
import com.hrapp.hrmcpserver.dto.EmployeeDto;
import com.hrapp.hrmcpserver.dto.PageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Thin HTTP facade over employee-service. Only read operations are
 * exposed because the MCP server's chosen tool surface is read-only on
 * the employee side.
 */
@Component
@Slf4j
public class EmployeeClient {

    private static final ParameterizedTypeReference<PageDto<EmployeeDto>> EMPLOYEE_PAGE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public EmployeeClient(@Qualifier(RestClientsConfig.EMPLOYEE_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /** GET /api/employees/{id} — 404 collapses to {@code Optional.empty()}. */
    public Optional<EmployeeDto> getEmployeeById(Long employeeId) {
        try {
            log.debug("Fetching employee id={}", employeeId);
            return Optional.ofNullable(restClient.get()
                    .uri("/api/employees/{id}", employeeId)
                    .retrieve()
                    .body(EmployeeDto.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** GET /api/employees?page=&size=&sortBy=&sortDir= */
    public PageDto<EmployeeDto> listEmployees(int page, int size, String sortBy, String sortDir) {
        log.debug("Listing employees page={} size={} sortBy={} sortDir={}", page, size, sortBy, sortDir);
        return restClient.get()
                .uri(uri -> uri.path("/api/employees")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .queryParam("sortBy", sortBy)
                        .queryParam("sortDir", sortDir)
                        .build())
                .retrieve()
                .body(EMPLOYEE_PAGE_TYPE);
    }

    /** GET /api/employees/department/{department} */
    public List<EmployeeDto> getEmployeesByDepartment(String department) {
        log.debug("Fetching employees in department={}", department);
        EmployeeDto[] result = restClient.get()
                .uri("/api/employees/department/{department}", department)
                .retrieve()
                .body(EmployeeDto[].class);
        return result == null ? List.of() : Arrays.asList(result);
    }
}
