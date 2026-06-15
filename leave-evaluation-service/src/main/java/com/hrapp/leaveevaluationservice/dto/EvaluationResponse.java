package com.hrapp.leaveevaluationservice.dto;

import java.util.List;

public record EvaluationResponse(
        Outcome outcome,
        double confidenceScore,
        List<String> reasons,
        List<String> policySourcesUsed
) {
    public enum Outcome {
        APPROVE, REJECT
    }
}
