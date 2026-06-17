package com.hrapp.hrmcpserver.tools;

import com.hrapp.hrmcpserver.client.EvaluationClient;
import com.hrapp.hrmcpserver.dto.EvaluationRequestDto;
import com.hrapp.hrmcpserver.dto.EvaluationResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * MCP tool surface for the leave-evaluation microservice (RAG over
 * company policies + local LLM).
 *
 * Exactly one tool: an advisory "what-if" that runs the same RAG pipeline
 * `leave-service` invokes at apply-time, but without persisting anything.
 * The LLM client can use this to reason about a hypothetical leave, then
 * combine the recommendation with {@code approve_leave} / {@code reject_leave}
 * for the actual state transition.
 */
@Component
@Slf4j
public class EvaluationTools {

    private final EvaluationClient evaluationClient;

    public EvaluationTools(EvaluationClient evaluationClient) {
        this.evaluationClient = evaluationClient;
    }

    @Tool(
            name = "evaluate_leave_request",
            description = """
                    Ask the policy-aware AI advisor to evaluate a leave request and
                    return a structured recommendation. This tool is ADVISORY ONLY:
                    it does NOT create, modify, approve, or cancel any leave. It runs
                    a RAG pipeline over the company's ingested policy PDFs plus a
                    local LLM, and returns:

                      - outcome: APPROVE or REJECT
                      - confidenceScore: a number between 0.0 and 1.0
                      - reasons: short, policy-grounded justification strings
                      - policySourcesUsed: which policy documents informed the answer

                    Typical uses:
                      - "Would this leave likely be approved?" (what-if for an employee)
                      - "Re-evaluate leave request 42 with current policies"
                      - As a reasoning aid before invoking approve_leave / reject_leave.

                    Performance: the underlying LLM may take 20-30 seconds on a cold
                    start (model load) but typically responds in a few seconds once
                    warm. If the evaluation service or LLM is unavailable, an error
                    is returned and you should report it back to the user rather than
                    retry blindly.
                    """
    )
    public EvaluationResponseDto evaluateLeaveRequest(
            @ToolParam(description = "The employee's numeric ID (positive integer).")
            Long employeeId,
            @ToolParam(description = "Leave type. One of: ANNUAL, SICK, MATERNITY, PATERNITY, UNPAID, EMERGENCY.")
            String leaveType,
            @ToolParam(description = "Start date in ISO format (yyyy-MM-dd).")
            LocalDate startDate,
            @ToolParam(description = "End date in ISO format (yyyy-MM-dd). Must be on or after startDate.")
            LocalDate endDate,
            @ToolParam(
                    required = false,
                    description = "Free-text reason the employee gave for the leave. Optional but improves the quality of the recommendation."
            ) String reason,
            @ToolParam(
                    required = false,
                    description = "Optional existing leave request ID, when re-evaluating a persisted leave. Omit for purely hypothetical what-if evaluations."
            ) Long leaveId) {
        log.info("Tool evaluate_leave_request employeeId={} type={} {}->{} leaveId={}",
                employeeId, leaveType, startDate, endDate, leaveId);
        EvaluationRequestDto request = EvaluationRequestDto.builder()
                .leaveId(leaveId)
                .employeeId(employeeId)
                .leaveType(leaveType)
                .startDate(startDate)
                .endDate(endDate)
                .reason(reason)
                .build();
        return evaluationClient.evaluate(request);
    }
}
