package com.hrapp.leaveservice.service;

import com.hrapp.leaveservice.client.EmployeeClient;
import com.hrapp.leaveservice.entity.LeaveRequest;
import com.hrapp.leaveservice.exception.ResourceNotFoundException;
import com.hrapp.leaveservice.repository.LeaveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {
    private final LeaveRepository leaveRepository;
    private final EmployeeClient employeeClient;

    public LeaveRequest applyForLeave(LeaveRequest request) {
        log.info("Processing leave request for employee: {}",
                request.getEmployeeId());

        // Validate employee exists
        employeeClient.getEmployeeById(request.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee", "id", request.getEmployeeId()));

        // Validate dates
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException(
                    "End date cannot be before start date");
        }

        request.setStatus(LeaveRequest.LeaveStatus.PENDING);
        return leaveRepository.save(request);
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
