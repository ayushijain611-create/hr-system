package com.hrapp.leaveevaluationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrapp.leaveevaluationservice.client.EmployeeClient;
import com.hrapp.leaveevaluationservice.client.LeaveHistoryClient;
import com.hrapp.leaveevaluationservice.dto.EmployeeDTO;
import com.hrapp.leaveevaluationservice.dto.EvaluationRequest;
import com.hrapp.leaveevaluationservice.dto.EvaluationResponse;
import com.hrapp.leaveevaluationservice.dto.EvaluationResponse.Outcome;
import com.hrapp.leaveevaluationservice.dto.LeaveSummaryDTO;
import com.hrapp.leaveevaluationservice.service.LeaveEvaluationService.LlmEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveEvaluationServiceTest {

    @Mock VectorStore vectorStore;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock EmployeeClient employeeClient;
    @Mock LeaveHistoryClient leaveHistoryClient;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks LeaveEvaluationService service;

    @BeforeEach
    void wireChatClient() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    // ---------- helpers ----------

    private static EvaluationRequest request(String type, LocalDate start, LocalDate end, String reason) {
        return new EvaluationRequest(42L, 7L, type, start, end, reason);
    }

    private static EvaluationRequest defaultRequest() {
        return request("ANNUAL", LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 17), "Family trip");
    }

    private static EmployeeDTO employee() {
        return new EmployeeDTO(7L, "Jane", "Doe", "j@x.com", "Engineering", "Senior Engineer", "ACTIVE");
    }

    private static Document chunk(String text, String source) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", source);
        return Document.builder().text(text).metadata(meta).build();
    }

    private static Document chunkWithoutSource(String text) {
        return Document.builder().text(text).metadata(new HashMap<>()).build();
    }

    private static LeaveSummaryDTO leave(long id, LocalDate start, LocalDate end, String status) {
        return new LeaveSummaryDTO(id, 99L, "ANNUAL", start, end, status, 1L);
    }

    private void stubChunks(List<Document> chunks) {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(chunks);
    }

    // Stubs the LLM's raw textual response by serialising the structured
    // record back to JSON - mirrors how the real Ollama call returns a
    // String to .content() that the service then parses.
    private void stubLlm(LlmEvaluation result) {
        if (result == null) {
            when(callSpec.content()).thenReturn(null);
            return;
        }
        try {
            String json = new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "outcome", result.outcome() == null ? "" : result.outcome().name(),
                    "confidenceScore", result.confidenceScore(),
                    "reasons", result.reasons() == null ? java.util.List.of() : result.reasons()
            ));
            when(callSpec.content()).thenReturn(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubLlmRaw(String raw) {
        when(callSpec.content()).thenReturn(raw);
    }

    // ================================================================
    //  HAPPY PATHS
    // ================================================================

    static Stream<Arguments> happyPathScenarios() {
        return Stream.of(
                Arguments.of(
                        "APPROVE short annual leave with 3 chunks across 2 sources",
                        request("ANNUAL", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3), "Wedding"),
                        new LlmEvaluation(Outcome.APPROVE, 0.92,
                                List.of("Within entitlement", "Sufficient notice")),
                        List.of(
                                chunk("annual leave 24 days", "Employee Leave Policy.pdf"),
                                chunk("notice 3 business days", "Employee Leave Policy.pdf"),
                                chunk("team coverage 60%", "Team Staffing and SLA Policy.pdf")
                        ),
                        Outcome.APPROVE,
                        0.92,
                        2,
                        List.of("Employee Leave Policy.pdf", "Team Staffing and SLA Policy.pdf")
                ),
                Arguments.of(
                        "REJECT long leave with insufficient notice",
                        request("ANNUAL", LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30), "Last-minute"),
                        new LlmEvaluation(Outcome.REJECT, 0.85,
                                List.of("Less than 10 business days notice", "Hits release freeze")),
                        List.of(
                                chunk("notice rules", "Employee Leave Policy.pdf"),
                                chunk("release freeze", "Project Delivery and Release Management Policy.pdf")
                        ),
                        Outcome.REJECT,
                        0.85,
                        2,
                        List.of("Employee Leave Policy.pdf", "Project Delivery and Release Management Policy.pdf")
                ),
                Arguments.of(
                        "APPROVE emergency leave even with short notice",
                        request("EMERGENCY", LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), "Family emergency"),
                        new LlmEvaluation(Outcome.APPROVE, 0.78,
                                List.of("Emergency leave allowed at management discretion")),
                        List.of(chunk("emergency leave discretion", "Employee Leave Policy.pdf")),
                        Outcome.APPROVE,
                        0.78,
                        1,
                        List.of("Employee Leave Policy.pdf")
                )
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("happyPathScenarios")
    void evaluatesEndToEnd(
            String scenario,
            EvaluationRequest req,
            LlmEvaluation llmReturn,
            List<Document> retrievedChunks,
            Outcome expectedOutcome,
            double expectedConfidence,
            int expectedSourceCount,
            List<String> expectedSources) {

        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(req.startDate(), req.endDate(), req.employeeId()))
                .thenReturn(List.of());
        stubChunks(retrievedChunks);
        stubLlm(llmReturn);

        EvaluationResponse result = service.evaluate(req);

        assertThat(result.outcome()).isEqualTo(expectedOutcome);
        assertThat(result.confidenceScore()).isEqualTo(expectedConfidence);
        assertThat(result.reasons()).isEqualTo(llmReturn.reasons());
        assertThat(result.policySourcesUsed())
                .hasSize(expectedSourceCount)
                .containsExactlyElementsOf(expectedSources);
    }

    // ================================================================
    //  CONFIDENCE CLAMPING
    // ================================================================

    static Stream<Arguments> confidenceClampingCases() {
        return Stream.of(
                        new double[]{0.0, 0.0},
                        new double[]{0.5, 0.5},
                        new double[]{1.0, 1.0},
                        new double[]{-0.5, 0.0},
                        new double[]{-0.0001, 0.0},
                        new double[]{1.0001, 1.0},
                        new double[]{42.0, 1.0},
                        new double[]{Double.NaN, 0.0},
                        new double[]{Double.NEGATIVE_INFINITY, 0.0},
                        new double[]{Double.POSITIVE_INFINITY, 1.0}
                )
                .map(pair -> Arguments.of(pair[0], pair[1]));
    }

    @ParameterizedTest(name = "[{index}] LLM={0} -> clamped={1}")
    @MethodSource("confidenceClampingCases")
    void clampsConfidenceScoreToUnitInterval(double llmRaw, double expected) {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy text", "p.pdf")));
        stubLlm(new LlmEvaluation(Outcome.APPROVE, llmRaw, List.of("r")));

        EvaluationResponse result = service.evaluate(req);

        assertThat(result.confidenceScore()).isEqualTo(expected);
    }

    // ================================================================
    //  SOURCE DEDUPLICATION + ORDERING
    // ================================================================

    static Stream<Arguments> sourceDedupCases() {
        return Stream.of(
                Arguments.of(
                        "preserves first-seen order, dedupes",
                        List.of(
                                chunk("c1", "B.pdf"),
                                chunk("c2", "A.pdf"),
                                chunk("c3", "B.pdf"),
                                chunk("c4", "A.pdf")
                        ),
                        List.of("B.pdf", "A.pdf")
                ),
                Arguments.of(
                        "single chunk, single source",
                        List.of(chunk("solo", "only.pdf")),
                        List.of("only.pdf")
                ),
                Arguments.of(
                        "no chunks at all",
                        List.<Document>of(),
                        List.<String>of()
                ),
                Arguments.of(
                        "chunk without source falls back to 'unknown'",
                        List.of(
                                chunk("c1", "real.pdf"),
                                chunkWithoutSource("orphan")
                        ),
                        List.of("real.pdf", "unknown")
                )
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("sourceDedupCases")
    void dedupesPolicySourcesPreservingFirstSeenOrder(
            String scenario, List<Document> chunks, List<String> expectedSources) {

        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(chunks);
        stubLlm(new LlmEvaluation(Outcome.APPROVE, 0.7, List.of("r")));

        EvaluationResponse result = service.evaluate(req);

        assertThat(result.policySourcesUsed()).containsExactlyElementsOf(expectedSources);
    }

    // ================================================================
    //  MISSING / DEGRADED CONTEXT
    // ================================================================

    // Tightened prompt drops sections entirely when their data is absent
    // (saves LLM tokens / latency). We assert presence/absence of the
    // section header instead of a verbose "(no data)" message.
    static Stream<Arguments> missingContextCases() {
        return Stream.of(
                Arguments.of("employee not found - EMPLOYEE section omitted",
                        Optional.<EmployeeDTO>empty(),
                        List.<LeaveSummaryDTO>of(),
                        List.<LeaveSummaryDTO>of(),
                        "EMPLOYEE:",
                        false),
                Arguments.of("no past leaves - PAST_LEAVES section omitted",
                        Optional.of(employee()),
                        List.<LeaveSummaryDTO>of(),
                        List.<LeaveSummaryDTO>of(),
                        "PAST_LEAVES:",
                        false),
                Arguments.of("no overlapping leaves - OVERLAPPING section omitted",
                        Optional.of(employee()),
                        List.of(leave(1L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), "APPROVED")),
                        List.<LeaveSummaryDTO>of(),
                        "OVERLAPPING_APPROVED_TEAM_LEAVES:",
                        false),
                Arguments.of("with past leaves - PAST_LEAVES section present",
                        Optional.of(employee()),
                        List.of(leave(1L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), "APPROVED")),
                        List.<LeaveSummaryDTO>of(),
                        "PAST_LEAVES:",
                        true),
                Arguments.of("with employee - EMPLOYEE section present",
                        Optional.of(employee()),
                        List.<LeaveSummaryDTO>of(),
                        List.<LeaveSummaryDTO>of(),
                        "EMPLOYEE:",
                        true)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("missingContextCases")
    void handlesMissingContextWithoutFailing(
            String scenario,
            Optional<EmployeeDTO> employeeResult,
            List<LeaveSummaryDTO> pastLeaves,
            List<LeaveSummaryDTO> overlapping,
            String sectionHeader,
            boolean shouldContain) {

        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(employeeResult);
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(pastLeaves);
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(overlapping);
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlm(new LlmEvaluation(Outcome.APPROVE, 0.6, List.of("r")));

        EvaluationResponse result = service.evaluate(req);

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(Outcome.APPROVE);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPromptCaptor.capture());
        if (shouldContain) {
            assertThat(userPromptCaptor.getValue()).contains(sectionHeader);
        } else {
            assertThat(userPromptCaptor.getValue()).doesNotContain(sectionHeader);
        }
    }

    // ================================================================
    //  PROMPT TRUNCATION
    // ================================================================

    @Test
    void truncatesPastAndOverlappingLeavesInPrompt() {
        EvaluationRequest req = defaultRequest();

        List<LeaveSummaryDTO> manyPast = IntStream.range(0, 25)
                .mapToObj(i -> leave((long) i,
                        LocalDate.of(2025, 1, 1).plusDays(i),
                        LocalDate.of(2025, 1, 2).plusDays(i),
                        "APPROVED"))
                .toList();
        List<LeaveSummaryDTO> manyOverlap = IntStream.range(0, 50)
                .mapToObj(i -> leave(1000L + i,
                        req.startDate(), req.endDate(), "APPROVED"))
                .toList();

        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(manyPast);
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(manyOverlap);
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlm(new LlmEvaluation(Outcome.APPROVE, 0.5, List.of("r")));

        service.evaluate(req);

        ArgumentCaptor<String> capture = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(capture.capture());
        String prompt = capture.getValue();

        // New compact format: "- 2025-01-05..2025-01-06 ANNUAL APPROVED"
        long pastLines = prompt.lines().filter(l -> l.startsWith("- ") && l.endsWith(" APPROVED")).count();
        // New compact format: "- emp=1234 2026-07-15..2026-07-17 ANNUAL"
        long overlapLines = prompt.lines().filter(l -> l.startsWith("- emp=")).count();

        assertThat(pastLines).isEqualTo(5);
        assertThat(overlapLines).isEqualTo(10);
    }

    // ================================================================
    //  RETRIEVAL CONFIGURATION
    // ================================================================

    @Test
    void invokesSimilaritySearchWithConfiguredTopK() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("x", "p.pdf")));
        stubLlm(new LlmEvaluation(Outcome.APPROVE, 0.5, List.of("r")));

        service.evaluate(req);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(4);
        assertThat(captor.getValue().getQuery())
                .contains("ANNUAL")
                .contains("Engineering")
                .contains("Senior Engineer");
    }

    // ================================================================
    //  INVALID INPUTS / ERROR PATHS
    // ================================================================

    @Test
    void throwsWhenLlmReturnsNullContent() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlm(null);

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void throwsWhenLlmReturnsBlankContent() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlmRaw("   \n\t  ");

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void throwsWhenLlmOutcomeIsMissingFromJson() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlmRaw("{\"confidenceScore\":0.5,\"reasons\":[\"r\"]}");

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no parseable evaluation");
    }

    @Test
    void throwsWhenLlmOutcomeIsInvalidEnumValue() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlmRaw("{\"outcome\":\"MAYBE\",\"confidenceScore\":0.5,\"reasons\":[\"r\"]}");

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid outcome enum");
    }

    @Test
    void throwsWhenLlmReturnsCompletelyInvalidJson() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlmRaw("Sorry, I cannot help with that.");

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Raw snippet");
    }

    @Test
    void propagatesChatClientFailure() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        when(callSpec.content()).thenThrow(new RuntimeException("Ollama exploded"));

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Ollama exploded");
    }

    // ================================================================
    //  LLM OUTPUT TOLERANT PARSING
    // ================================================================

    static Stream<Arguments> tolerantParsingCases() {
        return Stream.of(
                Arguments.of("clean JSON",
                        "{\"outcome\":\"APPROVE\",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}"),
                Arguments.of("markdown json fence",
                        "```json\n{\"outcome\":\"APPROVE\",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}\n```"),
                Arguments.of("markdown bare fence",
                        "```\n{\"outcome\":\"APPROVE\",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}\n```"),
                Arguments.of("prose before json",
                        "Here is my evaluation:\n{\"outcome\":\"APPROVE\",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}"),
                Arguments.of("prose after json",
                        "{\"outcome\":\"APPROVE\",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}\nLet me know if you need more detail."),
                Arguments.of("lowercase outcome",
                        "{\"outcome\":\"approve\",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}"),
                Arguments.of("padded outcome string",
                        "{\"outcome\":\"  APPROVE  \",\"confidenceScore\":0.7,\"reasons\":[\"a\",\"b\"]}")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("tolerantParsingCases")
    void tolerantlyParsesCommonLlmOutputVariations(String scenario, String rawOutput) {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlmRaw(rawOutput);

        EvaluationResponse result = service.evaluate(req);

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(result.confidenceScore()).isEqualTo(0.7);
        assertThat(result.reasons()).containsExactly("a", "b");
    }

    static Stream<Arguments> nullishRequestFieldCases() {
        LocalDate s = LocalDate.of(2026, 7, 15);
        LocalDate e = LocalDate.of(2026, 7, 17);
        return Stream.of(
                Arguments.of("null reason",
                        new EvaluationRequest(1L, 7L, "ANNUAL", s, e, null)),
                Arguments.of("blank reason",
                        new EvaluationRequest(1L, 7L, "ANNUAL", s, e, "")),
                Arguments.of("null leaveId (eval still works)",
                        new EvaluationRequest(null, 7L, "ANNUAL", s, e, "r")),
                Arguments.of("single-day leave (start == end)",
                        new EvaluationRequest(1L, 7L, "SICK", s, s, "Doctor visit"))
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nullishRequestFieldCases")
    void handlesNullishOrEdgeRequestFields(String scenario, EvaluationRequest req) {
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        stubChunks(List.of(chunk("policy", "p.pdf")));
        stubLlm(new LlmEvaluation(Outcome.APPROVE, 0.5, List.of("r")));

        EvaluationResponse result = service.evaluate(req);

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(Outcome.APPROVE);
    }

    @Test
    void doesNotCallLlmWhenSimilaritySearchFails() {
        EvaluationRequest req = defaultRequest();
        when(employeeClient.getEmployeeById(req.employeeId())).thenReturn(Optional.of(employee()));
        when(leaveHistoryClient.getLeavesForEmployee(req.employeeId())).thenReturn(List.of());
        when(leaveHistoryClient.searchOverlappingApproved(any(), any(), any())).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("pgvector down"));

        assertThatThrownBy(() -> service.evaluate(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("pgvector down");

        verify(callSpec, never()).content();
    }
}
