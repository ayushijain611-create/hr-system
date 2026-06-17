package com.hrapp.hrmcpserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaveRequestDto {
    private Long id;
    private Long employeeId;
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private String status;
    private LocalDateTime appliedAt;
    private LocalDateTime reviewedAt;
    private String reviewerComments;
    private long totalDays;

    // AI advisory fields (populated by leave-evaluation-service at apply-time;
    // null when the AI was unavailable or for legacy rows).
    private String aiOutcome;
    private Double aiConfidenceScore;
    private List<String> aiReasons;
}
