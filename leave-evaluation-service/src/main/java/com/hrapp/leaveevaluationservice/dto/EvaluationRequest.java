package com.hrapp.leaveevaluationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record EvaluationRequest(
        Long leaveId,
        @NotNull Long employeeId,
        @NotBlank String leaveType,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String reason
) {
}
