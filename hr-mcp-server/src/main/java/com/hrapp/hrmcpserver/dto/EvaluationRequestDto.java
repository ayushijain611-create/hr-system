package com.hrapp.hrmcpserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request body for `POST /api/evaluations` on leave-evaluation-service.
 * `leaveId` is optional — present when the caller already has a persisted
 * leave id, omitted for ad-hoc what-if evaluations from the MCP client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRequestDto {
    private Long leaveId;
    private Long employeeId;
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
}
