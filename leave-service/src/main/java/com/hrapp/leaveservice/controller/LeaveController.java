package com.hrapp.leaveservice.controller;

import com.hrapp.leaveservice.entity.LeaveRequest;
import com.hrapp.leaveservice.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/leaves")
public class LeaveController {
    private final LeaveService leaveService;

    @PostMapping
    public ResponseEntity<LeaveRequest> applyForLeave(
            @Valid @RequestBody LeaveRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(leaveService.applyForLeave(request));
    }

    @GetMapping
    public ResponseEntity<Page<LeaveRequest>> getAllLeaves(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(leaveService.getAllLeaves(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LeaveRequest> getLeaveById(
            @PathVariable Long id) {
        return ResponseEntity.ok(leaveService.getLeaveById(id));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<LeaveRequest>> getLeavesByEmployee(
            @PathVariable Long employeeId) {
        return ResponseEntity.ok(
                leaveService.getLeavesByEmployee(employeeId));
    }

    @GetMapping("/search")
    public ResponseEntity<List<LeaveRequest>> searchLeaves(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) LeaveRequest.LeaveStatus status,
            @RequestParam(required = false) Long excludeEmployeeId) {
        return ResponseEntity.ok(
                leaveService.searchLeaves(startDate, endDate, status, excludeEmployeeId));
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<LeaveRequest> approveLeave(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String comments = body.getOrDefault("comments", "Approved");
        return ResponseEntity.ok(
                leaveService.approveLeave(id, comments));
    }

    @PutMapping("/{id}/reject")
    public ResponseEntity<LeaveRequest> rejectLeave(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String comments = body.getOrDefault("comments", "Rejected");
        return ResponseEntity.ok(
                leaveService.rejectLeave(id, comments));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<LeaveRequest> cancelLeave(
            @PathVariable Long id) {
        return ResponseEntity.ok(leaveService.cancelLeave(id));
    }

}
