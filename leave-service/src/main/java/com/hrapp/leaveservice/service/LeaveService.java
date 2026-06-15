package com.hrapp.leaveservice.service;

import com.hrapp.leaveservice.client.EmployeeClient;
import com.hrapp.leaveservice.client.EvaluationClient;
import com.hrapp.leaveservice.dto.EvaluationRequestDTO;
import com.hrapp.leaveservice.dto.EvaluationResponseDTO;
import com.hrapp.leaveservice.entity.LeaveRequest;
import com.hrapp.leaveservice.exception.ResourceNotFoundException;
import com.hrapp.leaveservice.repository.LeaveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {
    private final LeaveRepository leaveRepository;
    private final EmployeeClient employeeClient;
    private final EvaluationClient evaluationClient;

    public LeaveRequest applyForLeave(LeaveRequest request) {
        log.info("Processing leave request for employee: {}",
                request.getEmployeeId());

        // Validate employee exists and is active
        var employee = employeeClient.getEmployeeById(request.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee", "id", request.getEmployeeId()));

        if (!"ACTIVE".equalsIgnoreCase(employee.getStatus())) {
            throw new IllegalStateException(
                    "Leave can only be applied for ACTIVE employees");
        }

        // Validate dates
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException(
                    "End date cannot be before start date");
        }

        request.setStatus(LeaveRequest.LeaveStatus.PENDING);
        LeaveRequest saved = leaveRepository.save(request);

        // Attach AI recommendation (advisory only; status stays PENDING regardless).
        return attachAiRecommendation(saved);
    }

    // Calls leave-evaluation-service synchronously; on success persists the AI fields, on failure logs and returns the leave unchanged.
    private LeaveRequest attachAiRecommendation(LeaveRequest leave) {
        EvaluationRequestDTO req = EvaluationRequestDTO.builder()
                .leaveId(leave.getId())
                .employeeId(leave.getEmployeeId())
                .leaveType(leave.getLeaveType().name())
                .startDate(leave.getStartDate())
                .endDate(leave.getEndDate())
                .reason(leave.getReason())
                .build();

        return evaluationClient.evaluate(req)
                .map(eval -> persistEvaluation(leave, eval))
                .orElse(leave);
    }

    private LeaveRequest persistEvaluation(LeaveRequest leave, EvaluationResponseDTO eval) {
        try {
            if (eval.getOutcome() != null) {
                leave.setAiOutcome(LeaveRequest.AiOutcome.valueOf(eval.getOutcome().toUpperCase()));
            }
            leave.setAiConfidenceScore(eval.getConfidenceScore());
            leave.setAiReasons(eval.getReasons());
            LeaveRequest persisted = leaveRepository.save(leave);
            log.info("AI recommendation persisted for leave id={}: outcome={}, confidence={}",
                    persisted.getId(), persisted.getAiOutcome(), persisted.getAiConfidenceScore());
            return persisted;
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised AI outcome '{}' for leave id={}; skipping AI fields",
                    eval.getOutcome(), leave.getId());
            return leave;
        }
    }

    public List<LeaveRequest> searchLeaves(
            LocalDate startDate,
            LocalDate endDate,
            LeaveRequest.LeaveStatus status,
            Long excludeEmployeeId) {
        return leaveRepository.searchOverlapping(startDate, endDate, status, excludeEmployeeId);
    }

    public LeaveRequest approveLeave(Long leaveId, String comments) {
        log.info("Approving leave request: {}", leaveId);
        LeaveRequest leave = getLeaveById(leaveId);

        if (leave.getStatus() != LeaveRequest.LeaveStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING leave requests can be approved");
        }

        leave.setStatus(LeaveRequest.LeaveStatus.APPROVED);
        leave.setReviewedAt(LocalDateTime.now());
        leave.setReviewerComments(comments);
        return leaveRepository.save(leave);
    }

    public LeaveRequest rejectLeave(Long leaveId, String comments) {
        log.info("Rejecting leave request: {}", leaveId);
        LeaveRequest leave = getLeaveById(leaveId);

        if (leave.getStatus() != LeaveRequest.LeaveStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING leave requests can be rejected");
        }

        leave.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        leave.setReviewedAt(LocalDateTime.now());
        leave.setReviewerComments(comments);
        return leaveRepository.save(leave);
    }

    public LeaveRequest cancelLeave(Long leaveId) {
        log.info("Cancelling leave request: {}", leaveId);
        LeaveRequest leave = getLeaveById(leaveId);

        if (leave.getStatus() != LeaveRequest.LeaveStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING leave requests can be cancelled");
        }

        leave.setStatus(LeaveRequest.LeaveStatus.CANCELLED);
        return leaveRepository.save(leave);
    }

    public LeaveRequest getLeaveById(Long id) {
        return leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "LeaveRequest", "id", id));
    }

    public List<LeaveRequest> getLeavesByEmployee(Long employeeId) {
        return leaveRepository.findByEmployeeId(employeeId);
    }

    public Page<LeaveRequest> getAllLeaves(int page, int size) {
        return leaveRepository.findAll(
                PageRequest.of(page, size,
                        Sort.by("appliedAt").descending()));
    }


}
