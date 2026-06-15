package com.hrapp.leaveservice.entity;

import com.hrapp.leaveservice.util.StringListJsonConverter;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "leave_requests")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LeaveRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Employee ID is required")
    @Column(nullable = false)
    private Long employeeId;

    @NotNull(message = "Leave type is required")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveType leaveType;

    @NotNull(message = "Start date is required")
    @Column(nullable = false)
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    @Column(nullable = false)
    private LocalDate endDate;

    @NotBlank(message = "Reason is required")
    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status = LeaveStatus.PENDING;

    @Builder.Default
    @Column(updatable = false)
    private LocalDateTime appliedAt = LocalDateTime.now();

    private LocalDateTime reviewedAt;

    private String reviewerComments;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_outcome")
    private AiOutcome aiOutcome;

    @Column(name = "ai_confidence_score")
    private Double aiConfidenceScore;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "ai_reasons", columnDefinition = "TEXT")
    private List<String> aiReasons;

    @Transient
    private long totalDays;

    @PostLoad
    @PostPersist
    public void calculateTotalDays() {
        if (startDate != null && endDate != null) {
            this.totalDays = startDate.until(endDate).getDays() + 1;
        }
    }

    public enum LeaveType {
        ANNUAL, SICK, MATERNITY, PATERNITY, UNPAID, EMERGENCY
    }

    public enum LeaveStatus {
        PENDING, APPROVED, REJECTED, CANCELLED
    }

    public enum AiOutcome {
        APPROVE, REJECT
    }
}
