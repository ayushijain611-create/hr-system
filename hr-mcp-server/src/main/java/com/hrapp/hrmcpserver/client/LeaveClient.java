package com.hrapp.hrmcpserver.client;

import com.hrapp.hrmcpserver.config.RestClientsConfig;
import com.hrapp.hrmcpserver.dto.LeaveRequestDto;
import com.hrapp.hrmcpserver.dto.PageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin HTTP facade over leave-service. Exposes the read endpoints plus
 * the three state-transition endpoints (approve / reject / cancel) that
 * the MCP tool surface needs.
 *
 * Notably absent: `applyForLeave`. Leave creation is intentionally not
 * part of the MCP tool surface for this iteration.
 */
@Component
@Slf4j
public class LeaveClient {

    private static final ParameterizedTypeReference<PageDto<LeaveRequestDto>> LEAVE_PAGE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public LeaveClient(@Qualifier(RestClientsConfig.LEAVE_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /** GET /api/leaves/{id} — 404 collapses to {@code Optional.empty()}. */
    public Optional<LeaveRequestDto> getLeaveById(Long leaveId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/api/leaves/{id}", leaveId)
                    .retrieve()
                    .body(LeaveRequestDto.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** GET /api/leaves?page=&size= */
    public PageDto<LeaveRequestDto> listLeaves(int page, int size) {
        return restClient.get()
                .uri(uri -> uri.path("/api/leaves")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(LEAVE_PAGE_TYPE);
    }

    /** GET /api/leaves/employee/{employeeId} */
    public List<LeaveRequestDto> getLeavesByEmployee(Long employeeId) {
        LeaveRequestDto[] result = restClient.get()
                .uri("/api/leaves/employee/{employeeId}", employeeId)
                .retrieve()
                .body(LeaveRequestDto[].class);
        return result == null ? List.of() : Arrays.asList(result);
    }

    /**
     * GET /api/leaves/search?startDate&endDate&status&excludeEmployeeId
     * `status` and `excludeEmployeeId` are optional and skipped when null/blank.
     */
    public List<LeaveRequestDto> searchLeaves(
            LocalDate startDate,
            LocalDate endDate,
            String status,
            Long excludeEmployeeId) {
        LeaveRequestDto[] result = restClient.get()
                .uri(uri -> {
                    UriBuilder b = uri.path("/api/leaves/search")
                            .queryParam("startDate", startDate)
                            .queryParam("endDate", endDate);
                    if (status != null && !status.isBlank()) {
                        b = b.queryParam("status", status);
                    }
                    if (excludeEmployeeId != null) {
                        b = b.queryParam("excludeEmployeeId", excludeEmployeeId);
                    }
                    return b.build();
                })
                .retrieve()
                .body(LeaveRequestDto[].class);
        return result == null ? List.of() : Arrays.asList(result);
    }

    /** PUT /api/leaves/{id}/approve  body: {"comments": "..."} */
    public LeaveRequestDto approveLeave(Long leaveId, String comments) {
        return transition(leaveId, "approve", comments, "Approved");
    }

    /** PUT /api/leaves/{id}/reject   body: {"comments": "..."} */
    public LeaveRequestDto rejectLeave(Long leaveId, String comments) {
        return transition(leaveId, "reject", comments, "Rejected");
    }

    /** PUT /api/leaves/{id}/cancel   (no body) */
    public LeaveRequestDto cancelLeave(Long leaveId) {
        return restClient.put()
                .uri("/api/leaves/{id}/cancel", leaveId)
                .retrieve()
                .body(LeaveRequestDto.class);
    }

    private LeaveRequestDto transition(Long leaveId, String action, String comments, String defaultComment) {
        String resolved = (comments == null || comments.isBlank()) ? defaultComment : comments;
        log.debug("Transitioning leave id={} action={}", leaveId, action);
        return restClient.put()
                .uri("/api/leaves/{id}/{action}", leaveId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("comments", resolved))
                .retrieve()
                .body(LeaveRequestDto.class);
    }
}
