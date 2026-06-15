package com.hrapp.leaveservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluationResponseDTO {
    private String outcome;
    private Double confidenceScore;
    private List<String> reasons;
    private List<String> policySourcesUsed;
}
