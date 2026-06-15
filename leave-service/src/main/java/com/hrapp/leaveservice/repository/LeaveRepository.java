package com.hrapp.leaveservice.repository;

import com.hrapp.leaveservice.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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

    /**
     * Finds leaves whose date range overlaps the given window. Two ranges overlap
     * when each starts on or before the other ends. {@code status} and
     * {@code excludeEmployeeId} are optional filters; pass {@code null} to skip them.
     */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE l.startDate <= :endDate
              AND l.endDate >= :startDate
              AND (:status IS NULL OR l.status = :status)
              AND (:excludeEmployeeId IS NULL OR l.employeeId <> :excludeEmployeeId)
            ORDER BY l.startDate ASC
            """)
    List<LeaveRequest> searchOverlapping(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") LeaveRequest.LeaveStatus status,
            @Param("excludeEmployeeId") Long excludeEmployeeId);
}
