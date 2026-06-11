package com.hrapp.leaveservice;

import com.hrapp.leaveservice.client.EmployeeClient;
import com.hrapp.leaveservice.dto.EmployeeDTO;
import com.hrapp.leaveservice.entity.LeaveRequest;
import com.hrapp.leaveservice.exception.ResourceNotFoundException;
import com.hrapp.leaveservice.repository.LeaveRepository;
import com.hrapp.leaveservice.service.LeaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveServiceApplicationTests {

    @Mock
    private LeaveRepository leaveRepository;

    @Mock
    private EmployeeClient employeeClient;

    @InjectMocks
    private LeaveService leaveService;

    private EmployeeDTO activeEmployee;
    private LeaveRequest pendingLeave;

    @BeforeEach
    void setUp() {
        activeEmployee = new EmployeeDTO(1L, "Jane", "Doe",
                "jane@example.com", "Engineering", "Engineer", "ACTIVE");

        pendingLeave = LeaveRequest.builder()
                .employeeId(1L)
                .leaveType(LeaveRequest.LeaveType.ANNUAL)
                .startDate(LocalDate.of(2026, 7, 1))
                .endDate(LocalDate.of(2026, 7, 5))
                .reason("Vacation")
                .status(LeaveRequest.LeaveStatus.PENDING)
                .appliedAt(LocalDateTime.now())
                .build();
    }

    // ── applyForLeave ─────────────────────────────────────────────────────────

    @Test
    void applyForLeave_validRequest_returnsSavedLeave() {
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(activeEmployee));
        when(leaveRepository.save(any(LeaveRequest.class))).thenReturn(pendingLeave);

        LeaveRequest result = leaveService.applyForLeave(pendingLeave);

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.PENDING);
        verify(leaveRepository).save(pendingLeave);
    }

    @Test
    void applyForLeave_employeeNotFound_throwsResourceNotFoundException() {
        when(employeeClient.getEmployeeById(99L)).thenReturn(Optional.empty());
        pendingLeave.setEmployeeId(99L);

        assertThatThrownBy(() -> leaveService.applyForLeave(pendingLeave))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(leaveRepository, never()).save(any());
    }

    @Test
    void applyForLeave_inactiveEmployee_throwsIllegalStateException() {
        EmployeeDTO inactiveEmployee = new EmployeeDTO(1L, "Jane", "Doe",
                "jane@example.com", "Engineering", "Engineer", "INACTIVE");
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(inactiveEmployee));

        assertThatThrownBy(() -> leaveService.applyForLeave(pendingLeave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");

        verify(leaveRepository, never()).save(any());
    }

    @Test
    void applyForLeave_onLeaveEmployee_throwsIllegalStateException() {
        EmployeeDTO onLeaveEmployee = new EmployeeDTO(1L, "Jane", "Doe",
                "jane@example.com", "Engineering", "Engineer", "ON_LEAVE");
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(onLeaveEmployee));

        assertThatThrownBy(() -> leaveService.applyForLeave(pendingLeave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");

        verify(leaveRepository, never()).save(any());
    }

    @Test
    void applyForLeave_endDateBeforeStartDate_throwsIllegalArgumentException() {
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(activeEmployee));
        pendingLeave.setStartDate(LocalDate.of(2026, 7, 10));
        pendingLeave.setEndDate(LocalDate.of(2026, 7, 1));

        assertThatThrownBy(() -> leaveService.applyForLeave(pendingLeave))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("End date");

        verify(leaveRepository, never()).save(any());
    }

    @Test
    void applyForLeave_sameDayLeave_isAccepted() {
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(activeEmployee));
        pendingLeave.setStartDate(LocalDate.of(2026, 7, 1));
        pendingLeave.setEndDate(LocalDate.of(2026, 7, 1));
        when(leaveRepository.save(any())).thenReturn(pendingLeave);

        assertThatCode(() -> leaveService.applyForLeave(pendingLeave))
                .doesNotThrowAnyException();
    }

    // ── approveLeave ──────────────────────────────────────────────────────────

    @Test
    void approveLeave_pendingLeave_setsApprovedStatus() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));
        when(leaveRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LeaveRequest result = leaveService.approveLeave(1L, "Looks good");

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.APPROVED);
        assertThat(result.getReviewerComments()).isEqualTo("Looks good");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void approveLeave_alreadyApproved_throwsIllegalStateException() {
        pendingLeave.setStatus(LeaveRequest.LeaveStatus.APPROVED);
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        assertThatThrownBy(() -> leaveService.approveLeave(1L, "Again?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void approveLeave_notFound_throwsResourceNotFoundException() {
        when(leaveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveService.approveLeave(99L, "comments"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── rejectLeave ───────────────────────────────────────────────────────────

    @Test
    void rejectLeave_pendingLeave_setsRejectedStatus() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));
        when(leaveRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LeaveRequest result = leaveService.rejectLeave(1L, "Not enough notice");

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.REJECTED);
        assertThat(result.getReviewerComments()).isEqualTo("Not enough notice");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void rejectLeave_alreadyRejected_throwsIllegalStateException() {
        pendingLeave.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        assertThatThrownBy(() -> leaveService.rejectLeave(1L, "comments"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void rejectLeave_notFound_throwsResourceNotFoundException() {
        when(leaveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveService.rejectLeave(99L, "comments"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── cancelLeave ───────────────────────────────────────────────────────────

    @Test
    void cancelLeave_pendingLeave_setsCancelledStatus() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));
        when(leaveRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LeaveRequest result = leaveService.cancelLeave(1L);

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.CANCELLED);
    }

    @Test
    void cancelLeave_approvedLeave_throwsIllegalStateException() {
        pendingLeave.setStatus(LeaveRequest.LeaveStatus.APPROVED);
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        assertThatThrownBy(() -> leaveService.cancelLeave(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void cancelLeave_notFound_throwsResourceNotFoundException() {
        when(leaveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveService.cancelLeave(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getLeaveById ──────────────────────────────────────────────────────────

    @Test
    void getLeaveById_existingId_returnsLeave() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        LeaveRequest result = leaveService.getLeaveById(1L);

        assertThat(result).isEqualTo(pendingLeave);
    }

    @Test
    void getLeaveById_unknownId_throwsResourceNotFoundException() {
        when(leaveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveService.getLeaveById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── getLeavesByEmployee ───────────────────────────────────────────────────

    @Test
    void getLeavesByEmployee_returnsAllLeavesForEmployee() {
        when(leaveRepository.findByEmployeeId(1L)).thenReturn(List.of(pendingLeave));

        List<LeaveRequest> result = leaveService.getLeavesByEmployee(1L);

        assertThat(result).hasSize(1).containsExactly(pendingLeave);
    }

    @Test
    void getLeavesByEmployee_noLeaves_returnsEmptyList() {
        when(leaveRepository.findByEmployeeId(2L)).thenReturn(List.of());

        List<LeaveRequest> result = leaveService.getLeavesByEmployee(2L);

        assertThat(result).isEmpty();
    }

    // ── getAllLeaves ──────────────────────────────────────────────────────────

    @Test
    void getAllLeaves_returnsPaginatedResults() {
        Page<LeaveRequest> page = new PageImpl<>(List.of(pendingLeave));
        when(leaveRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<LeaveRequest> result = leaveService.getAllLeaves(0, 10);

        assertThat(result.getContent()).containsExactly(pendingLeave);
        verify(leaveRepository).findAll(any(Pageable.class));
    }
}
