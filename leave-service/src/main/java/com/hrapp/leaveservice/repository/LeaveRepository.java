package com.hrapp.leaveservice.repository;

import com.hrapp.leaveservice.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findByEmployeeId(Long employeeId);

    List<LeaveRequest> findByStatus(LeaveRequest.LeaveStatus status);

    List<LeaveRequest> findByEmployeeIdAndStatus(
            Long employeeId,
            LeaveRequest.LeaveStatus status);

    long countByEmployeeIdAndStatus(
            Long employeeId,
            LeaveRequest.LeaveStatus status);
}
