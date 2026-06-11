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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.function.BiConsumer;
import java.util.stream.Stream;

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

    // ── applyForLeave — happy path ────────────────────────────────────────────

    @Test
    void applyForLeave_validActiveEmployee_returnsPendingLeave() {
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(activeEmployee));
        when(leaveRepository.save(any())).thenReturn(pendingLeave);

        LeaveRequest result = leaveService.applyForLeave(pendingLeave);

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.PENDING);
        verify(leaveRepository).save(pendingLeave);
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

    // ── applyForLeave — non-ACTIVE employee statuses (parameterized) ───────────
    // Streams every non-active status to assert all are rejected with the
    // same IllegalStateException mentioning "ACTIVE".

    static Stream<Arguments> nonActiveEmployeeStatuses() {
        return Stream.of(
                Arguments.of("INACTIVE"),
                Arguments.of("ON_LEAVE")
        );
    }

    @ParameterizedTest(name = "employee status={0} → IllegalStateException")
    @MethodSource("nonActiveEmployeeStatuses")
    void applyForLeave_nonActiveEmployee_throwsIllegalStateException(String status) {
        EmployeeDTO nonActiveEmployee = new EmployeeDTO(1L, "Jane", "Doe",
                "jane@example.com", "Engineering", "Engineer", status);
        when(employeeClient.getEmployeeById(1L)).thenReturn(Optional.of(nonActiveEmployee));

        assertThatThrownBy(() -> leaveService.applyForLeave(pendingLeave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");

        verify(leaveRepository, never()).save(any());
    }

    // ── applyForLeave — employee not found ────────────────────────────────────

    @Test
    void applyForLeave_employeeNotFound_throwsResourceNotFoundException() {
        when(employeeClient.getEmployeeById(99L)).thenReturn(Optional.empty());
        pendingLeave.setEmployeeId(99L);

        assertThatThrownBy(() -> leaveService.applyForLeave(pendingLeave))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(leaveRepository, never()).save(any());
    }

    // ── applyForLeave — invalid date range ────────────────────────────────────

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

    // ── approve/reject/cancel — forbidden starting statuses (parameterized) ────
    // Each operation may only act on PENDING leaves. Streams APPROVED, REJECTED,
    // and CANCELLED as forbidden starting statuses for each operation.

    static Stream<Arguments> nonPendingStatuses() {
        return Stream.of(
                Arguments.of(LeaveRequest.LeaveStatus.APPROVED),
                Arguments.of(LeaveRequest.LeaveStatus.REJECTED),
                Arguments.of(LeaveRequest.LeaveStatus.CANCELLED)
        );
    }

    @ParameterizedTest(name = "approveLeave when status={0} → IllegalStateException")
    @MethodSource("nonPendingStatuses")
    void approveLeave_nonPendingLeave_throwsIllegalStateException(LeaveRequest.LeaveStatus status) {
        pendingLeave.setStatus(status);
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        assertThatThrownBy(() -> leaveService.approveLeave(1L, "comments"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @ParameterizedTest(name = "rejectLeave when status={0} → IllegalStateException")
    @MethodSource("nonPendingStatuses")
    void rejectLeave_nonPendingLeave_throwsIllegalStateException(LeaveRequest.LeaveStatus status) {
        pendingLeave.setStatus(status);
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        assertThatThrownBy(() -> leaveService.rejectLeave(1L, "comments"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @ParameterizedTest(name = "cancelLeave when status={0} → IllegalStateException")
    @MethodSource("nonPendingStatuses")
    void cancelLeave_nonPendingLeave_throwsIllegalStateException(LeaveRequest.LeaveStatus status) {
        pendingLeave.setStatus(status);
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));

        assertThatThrownBy(() -> leaveService.cancelLeave(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    // ── not-found cases across all operations (parameterized) ─────────────────
    // Streams a lambda per service operation so all "unknown ID →
    // ResourceNotFoundException" paths are covered from a single test method.

    static Stream<Arguments> notFoundOperations() {
        return Stream.of(
                Arguments.of("getLeaveById",  (BiConsumer<LeaveService, Long>) (svc, id) -> svc.getLeaveById(id)),
                Arguments.of("approveLeave",  (BiConsumer<LeaveService, Long>) (svc, id) -> svc.approveLeave(id, "c")),
                Arguments.of("rejectLeave",   (BiConsumer<LeaveService, Long>) (svc, id) -> svc.rejectLeave(id, "c")),
                Arguments.of("cancelLeave",   (BiConsumer<LeaveService, Long>) (svc, id) -> svc.cancelLeave(id))
        );
    }

    @ParameterizedTest(name = "{0} with unknown id → ResourceNotFoundException")
    @MethodSource("notFoundOperations")
    void leaveOperations_unknownId_throwsResourceNotFoundException(
            String name, BiConsumer<LeaveService, Long> operation) {
        when(leaveRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operation.accept(leaveService, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── approveLeave — happy path ─────────────────────────────────────────────

    @Test
    void approveLeave_pendingLeave_setsApprovedStatusAndReviewFields() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));
        when(leaveRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LeaveRequest result = leaveService.approveLeave(1L, "Looks good");

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.APPROVED);
        assertThat(result.getReviewerComments()).isEqualTo("Looks good");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    // ── rejectLeave — happy path ──────────────────────────────────────────────

    @Test
    void rejectLeave_pendingLeave_setsRejectedStatusAndReviewFields() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));
        when(leaveRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LeaveRequest result = leaveService.rejectLeave(1L, "Not enough notice");

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.REJECTED);
        assertThat(result.getReviewerComments()).isEqualTo("Not enough notice");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    // ── cancelLeave — happy path ──────────────────────────────────────────────

    @Test
    void cancelLeave_pendingLeave_setsCancelledStatus() {
        when(leaveRepository.findById(1L)).thenReturn(Optional.of(pendingLeave));
        when(leaveRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LeaveRequest result = leaveService.cancelLeave(1L);

        assertThat(result.getStatus()).isEqualTo(LeaveRequest.LeaveStatus.CANCELLED);
    }

    // ── getLeavesByEmployee — parameterized ────────────────────────────────────
    // Streams (employeeId, expectedSize) pairs to cover both found and empty cases.

    static Stream<Arguments> leavesByEmployeeScenarios() {
        return Stream.of(
                Arguments.of(1L, 1),
                Arguments.of(2L, 0)
        );
    }

    @ParameterizedTest(name = "employeeId={0} → {1} result(s)")
    @MethodSource("leavesByEmployeeScenarios")
    void getLeavesByEmployee_returnsExpectedCount(Long employeeId, int expectedSize) {
        List<LeaveRequest> mockResult = expectedSize > 0 ? List.of(pendingLeave) : List.of();
        when(leaveRepository.findByEmployeeId(employeeId)).thenReturn(mockResult);

        List<LeaveRequest> result = leaveService.getLeavesByEmployee(employeeId);

        assertThat(result).hasSize(expectedSize);
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
