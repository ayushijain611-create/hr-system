package com.hrapp.leaveevaluationservice.service;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrapp.leaveevaluationservice.client.EmployeeClient;
import com.hrapp.leaveevaluationservice.client.LeaveHistoryClient;
import com.hrapp.leaveevaluationservice.dto.EmployeeDTO;
import com.hrapp.leaveevaluationservice.dto.EvaluationRequest;
import com.hrapp.leaveevaluationservice.dto.EvaluationResponse;
import com.hrapp.leaveevaluationservice.dto.LeaveSummaryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveEvaluationService {

    private static final int RETRIEVAL_TOP_K = 4;
    private static final int MAX_PAST_LEAVES_IN_PROMPT = 5;
    private static final int MAX_OVERLAPPING_LEAVES_IN_PROMPT = 10;

    private static final String SYSTEM_PROMPT = """
            You are an HR leave-policy evaluator. Recommend APPROVE or REJECT using ONLY
            the policies and context provided. You are advisory; a manager decides.

            Rules:
            - Ground every reason in the supplied policies or context. Do not invent rules.
            - If policies do not clearly forbid the request, lean APPROVE.
            - Return 2-4 short reasons, one sentence each, max 20 words each.
            - confidenceScore is in [0.0, 1.0]: 1.0 = unambiguous, 0.5 = mixed, 0.0 = no signal.

            OUTPUT FORMAT - reply with ONLY a single JSON object matching this exact schema:
            {
              "outcome": "APPROVE",
              "confidenceScore": 0.85,
              "reasons": ["short reason one", "short reason two"]
            }

            Field rules:
            - "outcome": MUST be the string "APPROVE" or "REJECT" (uppercase, no other values).
            - "confidenceScore": MUST be a number between 0.0 and 1.0 inclusive.
            - "reasons": MUST be an array of 2-4 short strings, each under 20 words.
            - Do NOT include any other fields, prose, markdown fences, comments, or trailing text.
            - Do NOT wrap the JSON in ```json``` fences.
            - The entire response must start with '{' and end with '}'.
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final EmployeeClient employeeClient;
    private final LeaveHistoryClient leaveHistoryClient;
    private final ObjectMapper objectMapper;

    // Main entry point: gathers context, retrieves relevant policy chunks, asks the LLM, and returns a structured recommendation.
    public EvaluationResponse evaluate(EvaluationRequest request) {
        log.info("Evaluating leave id={} for employee={}", request.leaveId(), request.employeeId());

        EmployeeDTO employee = employeeClient.getEmployeeById(request.employeeId()).orElse(null);
        List<LeaveSummaryDTO> pastLeaves = leaveHistoryClient.getLeavesForEmployee(request.employeeId());
        List<LeaveSummaryDTO> overlapping = leaveHistoryClient.searchOverlappingApproved(
                request.startDate(), request.endDate(), request.employeeId());

        String retrievalQuery = buildRetrievalQuery(request, employee);
        List<Document> policyChunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(retrievalQuery)
                        .topK(RETRIEVAL_TOP_K)
                        .build());
        log.info("Retrieved {} policy chunk(s) for evaluation", policyChunks.size());

        String userPrompt = buildUserPrompt(request, employee, pastLeaves, overlapping, policyChunks);

        String rawLlmOutput = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        if (log.isDebugEnabled()) {
            log.debug("Raw LLM output for leave id={}: {}", request.leaveId(), rawLlmOutput);
        }

        LlmEvaluation llmResult = parseLlmOutput(rawLlmOutput, request.leaveId());

        List<String> sourcesUsed = extractSources(policyChunks);
        log.info("LLM decision: {} (confidence={})", llmResult.outcome(), llmResult.confidenceScore());

        return new EvaluationResponse(
                llmResult.outcome(),
                clamp(llmResult.confidenceScore()),
                llmResult.reasons(),
                sourcesUsed
        );
    }

    // Parses the raw LLM string into an LlmEvaluation. On failure logs the
    // raw output so we can diagnose model misbehaviour, then throws a
    // descriptive IllegalStateException that includes a snippet of the raw
    // text. Tolerates common LLM mistakes: markdown code fences and
    // leading/trailing prose around the JSON object.
    private LlmEvaluation parseLlmOutput(String rawOutput, Long leaveId) {
        if (rawOutput == null || rawOutput.isBlank()) {
            log.error("LLM returned empty content for leave id={}", leaveId);
            throw new IllegalStateException(
                    "LLM returned empty response for leave id=" + leaveId);
        }

        String cleaned = stripFencesAndExtractJsonObject(rawOutput);

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String outcomeStr = node.path("outcome").asText(null);
            EvaluationResponse.Outcome outcome = outcomeStr == null
                    ? null
                    : EvaluationResponse.Outcome.valueOf(outcomeStr.trim().toUpperCase());
            double confidence = node.path("confidenceScore").asDouble(0.0);
            JsonNode reasonsNode = node.path("reasons");
            List<String> reasons = new java.util.ArrayList<>();
            if (reasonsNode.isArray()) {
                for (JsonNode r : reasonsNode) {
                    if (r.isTextual() && !r.asText().isBlank()) {
                        reasons.add(r.asText());
                    }
                }
            }
            if (outcome == null) {
                throw new IllegalStateException("Missing or invalid 'outcome' field");
            }
            return new LlmEvaluation(outcome, confidence, reasons);
        } catch (IllegalStateException ise) {
            log.error("LLM response missing required fields for leave id={}. Raw output: {}",
                    leaveId, rawOutput);
            throw new IllegalStateException(
                    "LLM returned no parseable evaluation for leave id=" + leaveId
                            + ". Issue: " + ise.getMessage()
                            + ". Raw snippet: " + snippet(rawOutput));
        } catch (IllegalArgumentException iae) {
            log.error("LLM returned invalid outcome value for leave id={}. Raw output: {}",
                    leaveId, rawOutput);
            throw new IllegalStateException(
                    "LLM returned invalid outcome enum for leave id=" + leaveId
                            + ". Raw snippet: " + snippet(rawOutput), iae);
        } catch (JsonMappingException jme) {
            log.error("LLM response is not valid JSON for leave id={}. Raw output: {}",
                    leaveId, rawOutput);
            throw new IllegalStateException(
                    "LLM returned non-JSON response for leave id=" + leaveId
                            + ". Raw snippet: " + snippet(rawOutput), jme);
        } catch (Exception e) {
            log.error("Unexpected error parsing LLM response for leave id={}. Raw output: {}",
                    leaveId, rawOutput, e);
            throw new IllegalStateException(
                    "LLM response parse failure for leave id=" + leaveId
                            + ". Raw snippet: " + snippet(rawOutput), e);
        }
    }

    // Best-effort cleanup: strips ```json ... ``` fences and isolates the
    // outermost {...} JSON object from any prose the model might emit.
    private String stripFencesAndExtractJsonObject(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
            s = s.trim();
        }
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1);
        }
        return s;
    }

    private String snippet(String text) {
        if (text == null) return "<null>";
        String trimmed = text.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
    }

    // Constructs the semantic-search query string used to retrieve relevant policy chunks from the vector store.
    private String buildRetrievalQuery(EvaluationRequest req, EmployeeDTO employee) {
        long days = ChronoUnit.DAYS.between(req.startDate(), req.endDate()) + 1;
        StringBuilder q = new StringBuilder();
        q.append("Leave request: type=").append(req.leaveType())
                .append(", duration=").append(days).append(" days")
                .append(", reason=").append(safe(req.reason()));
        if (employee != null) {
            q.append(", department=").append(safe(employee.getDepartment()))
                    .append(", role=").append(safe(employee.getJobTitle()));
        }
        return q.toString();
    }

    // Assembles the user-facing prompt sections: leave request, employee context, history, overlapping team leaves, and retrieved policies.
    private String buildUserPrompt(
            EvaluationRequest req,
            EmployeeDTO employee,
            List<LeaveSummaryDTO> pastLeaves,
            List<LeaveSummaryDTO> overlapping,
            List<Document> policyChunks) {
        long days = ChronoUnit.DAYS.between(req.startDate(), req.endDate()) + 1;
        long noticeDays = ChronoUnit.DAYS.between(LocalDate.now(), req.startDate());

        StringBuilder sb = new StringBuilder(2048);

        sb.append("REQUEST: ").append(req.leaveType())
                .append(" | ").append(req.startDate()).append(" to ").append(req.endDate())
                .append(" | ").append(days).append("d | notice=").append(noticeDays).append("d")
                .append(" | reason=").append(safe(req.reason())).append("\n");

        if (employee != null) {
            sb.append("EMPLOYEE: ")
                    .append(safe(employee.getFirstName())).append(" ").append(safe(employee.getLastName()))
                    .append(" | ").append(safe(employee.getJobTitle()))
                    .append(" | ").append(safe(employee.getDepartment()))
                    .append(" | ").append(safe(employee.getStatus())).append("\n");
        }

        List<LeaveSummaryDTO> recentPast = pastLeaves.stream()
                .sorted(Comparator.comparing(LeaveSummaryDTO::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_PAST_LEAVES_IN_PROMPT)
                .toList();
        if (!recentPast.isEmpty()) {
            sb.append("PAST_LEAVES:\n");
            for (LeaveSummaryDTO l : recentPast) {
                sb.append("- ").append(l.getStartDate()).append("..").append(l.getEndDate())
                        .append(" ").append(l.getLeaveType())
                        .append(" ").append(l.getStatus()).append("\n");
            }
        }

        List<LeaveSummaryDTO> overlapTrimmed = overlapping.stream()
                .limit(MAX_OVERLAPPING_LEAVES_IN_PROMPT)
                .toList();
        if (!overlapTrimmed.isEmpty()) {
            sb.append("OVERLAPPING_APPROVED_TEAM_LEAVES:\n");
            for (LeaveSummaryDTO l : overlapTrimmed) {
                sb.append("- emp=").append(l.getEmployeeId())
                        .append(" ").append(l.getStartDate()).append("..").append(l.getEndDate())
                        .append(" ").append(l.getLeaveType()).append("\n");
            }
        }

        sb.append("POLICIES:\n");
        if (policyChunks.isEmpty()) {
            sb.append("(none retrieved)\n");
        } else {
            for (int i = 0; i < policyChunks.size(); i++) {
                Document d = policyChunks.get(i);
                Object src = d.getMetadata().getOrDefault("source", "unknown");
                sb.append("[P").append(i + 1).append(" ").append(src).append("] ")
                        .append(d.getText()).append("\n");
            }
        }

        return sb.toString();
    }

    // Collects the distinct source filenames of the policy chunks the LLM was shown.
    private List<String> extractSources(List<Document> policyChunks) {
        Set<String> sources = policyChunks.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return List.copyOf(sources);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    // Internal record matching exactly what we want the LLM to return; sources are attached by the service, not by the model.
    public record LlmEvaluation(
            EvaluationResponse.Outcome outcome,
            double confidenceScore,
            List<String> reasons
    ) {
    }
}
