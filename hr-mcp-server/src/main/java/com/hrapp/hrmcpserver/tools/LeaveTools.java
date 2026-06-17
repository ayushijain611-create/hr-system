package com.hrapp.hrmcpserver.tools;

import com.hrapp.hrmcpserver.client.LeaveClient;
import com.hrapp.hrmcpserver.dto.LeaveRequestDto;
import com.hrapp.hrmcpserver.dto.PageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * MCP tool surface for the leave microservice. Exposes the read endpoints
 * plus the three state-transition operations (approve / reject / cancel).
 * `applyForLeave` is intentionally NOT exposed — leave creation is out of
 * scope for this iteration so the LLM cannot file leaves on behalf of users.
 *
 * Each description spells out enum values and date formats explicitly,
 * because they are the only contract the LLM has for what to pass.
 */
@Component
@Slf4j
public class LeaveTools {

    private final LeaveClient leaveClient;

    public LeaveTools(LeaveClient leaveClient) {
        this.leaveClient = leaveClient;
    }

    // ---------- Reads ----------

    @Tool(
            name = "get_leave_by_id",
            description = """
                    Fetch a single leave request by its numeric ID. Returns the full
                    leave record including employeeId, leaveType (ANNUAL, SICK,
                    MATERNITY, PATERNITY, UNPAID, EMERGENCY), startDate/endDate
                    (ISO yyyy-MM-dd), reason, status (PENDING, APPROVED, REJECTED,
                    CANCELLED), totalDays, reviewer comments if any, and the AI
                    advisory fields (aiOutcome, aiConfidenceScore, aiReasons) when
                    they were populated at apply-time. Raises an error if no leave
                    exists with the given ID.
                    """
    )
    public LeaveRequestDto getLeaveById(
            @ToolParam(description = "The leave request's numeric ID (positive integer).")
            Long leaveId) {
        log.debug("Tool get_leave_by_id leaveId={}", leaveId);
        return leaveClient.getLeaveById(leaveId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Leave request with id=" + leaveId + " was not found"));
    }

    @Tool(
            name = "list_leaves",
            description = """
                    List leave requests with pagination. Returns a page object
                    (content + totalElements + totalPages + number + size + first +
                    last). Use this for broad browsing. If you already know an
                    employee, prefer `get_leaves_by_employee`; if you need leaves
                    within a date window, prefer `search_leaves`.
                    """
    )
    public PageDto<LeaveRequestDto> listLeaves(
            @ToolParam(
                    required = false,
                    description = "Zero-based page number. Defaults to 0 when omitted."
            ) Integer page,
            @ToolParam(
                    required = false,
                    description = "Page size (1-100). Defaults to 10 when omitted; values above 100 are clamped."
            ) Integer size) {
        int resolvedPage = page == null ? 0 : Math.max(0, page);
        int resolvedSize = size == null ? 10 : Math.min(100, Math.max(1, size));
        log.debug("Tool list_leaves page={} size={}", resolvedPage, resolvedSize);
        return leaveClient.listLeaves(resolvedPage, resolvedSize);
    }

    @Tool(
            name = "get_leaves_by_employee",
            description = """
                    Return the complete leave history for a single employee, regardless
                    of status. Useful for answering questions like "show me Priya's leave
                    history" once the employee ID is known. Returns an empty list if the
                    employee has never filed a leave.
                    """
    )
    public List<LeaveRequestDto> getLeavesByEmployee(
            @ToolParam(description = "The employee's numeric ID (positive integer).")
            Long employeeId) {
        log.debug("Tool get_leaves_by_employee employeeId={}", employeeId);
        return leaveClient.getLeavesByEmployee(employeeId);
    }

    @Tool(
            name = "search_leaves",
            description = """
                    Find leave requests whose date range overlaps the given window.
                    Both startDate and endDate are required (ISO yyyy-MM-dd). Optionally
                    filter by status (PENDING, APPROVED, REJECTED, CANCELLED) and/or
                    exclude a specific employee (handy for asking "who else is out next
                    week besides me?"). Returns an empty list if nothing overlaps.
                    """
    )
    public List<LeaveRequestDto> searchLeaves(
            @ToolParam(description = "Window start date in ISO format (yyyy-MM-dd). Required.")
            LocalDate startDate,
            @ToolParam(description = "Window end date in ISO format (yyyy-MM-dd). Required and must be on or after startDate.")
            LocalDate endDate,
            @ToolParam(
                    required = false,
                    description = "Optional status filter. One of: PENDING, APPROVED, REJECTED, CANCELLED. Omit to include all statuses."
            ) String status,
            @ToolParam(
                    required = false,
                    description = "Optional employee ID to exclude from results (e.g. exclude the asking employee)."
            ) Long excludeEmployeeId) {
        log.debug("Tool search_leaves {}->{} status={} excludeEmployeeId={}",
                startDate, endDate, status, excludeEmployeeId);
        return leaveClient.searchLeaves(startDate, endDate, status, excludeEmployeeId);
    }

    // ---------- State transitions ----------

    @Tool(
            name = "approve_leave",
            description = """
                    Approve a PENDING leave request. The leave's status becomes APPROVED
                    and the provided comments are recorded as reviewerComments. Returns
                    the updated leave record so the caller can confirm the new state.
                    Only invoke after a human-confirmed decision; do not approve leaves
                    speculatively. Will fail if the leave does not exist or is not in
                    a state that can be approved (e.g. already REJECTED or CANCELLED).
                    """
    )
    public LeaveRequestDto approveLeave(
            @ToolParam(description = "The leave request's numeric ID (positive integer).")
            Long leaveId,
            @ToolParam(
                    required = false,
                    description = "Reviewer comments to record with the approval. Defaults to 'Approved' when omitted or blank."
            ) String comments) {
        log.info("Tool approve_leave leaveId={}", leaveId);
        return leaveClient.approveLeave(leaveId, comments);
    }

    @Tool(
            name = "reject_leave",
            description = """
                    Reject a PENDING leave request. The leave's status becomes REJECTED
                    and the provided comments are recorded as reviewerComments. Returns
                    the updated leave record so the caller can confirm the new state.
                    Only invoke after a human-confirmed decision. Will fail if the leave
                    does not exist or is not in a state that can be rejected.
                    """
    )
    public LeaveRequestDto rejectLeave(
            @ToolParam(description = "The leave request's numeric ID (positive integer).")
            Long leaveId,
            @ToolParam(
                    required = false,
                    description = "Reviewer comments to record with the rejection. Defaults to 'Rejected' when omitted or blank."
            ) String comments) {
        log.info("Tool reject_leave leaveId={}", leaveId);
        return leaveClient.rejectLeave(leaveId, comments);
    }

    @Tool(
            name = "cancel_leave",
            description = """
                    Cancel a leave request on the employee's behalf. The leave's status
                    becomes CANCELLED. Returns the updated leave record. Use this when
                    the employee no longer needs the leave; use `reject_leave` instead
                    when a manager is denying a pending request. Will fail if the leave
                    does not exist or is not in a state that can be cancelled.
                    """
    )
    public LeaveRequestDto cancelLeave(
            @ToolParam(description = "The leave request's numeric ID (positive integer).")
            Long leaveId) {
        log.info("Tool cancel_leave leaveId={}", leaveId);
        return leaveClient.cancelLeave(leaveId);
    }
}
