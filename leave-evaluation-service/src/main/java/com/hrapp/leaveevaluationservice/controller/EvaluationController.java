package com.hrapp.leaveevaluationservice.controller;

import com.hrapp.leaveevaluationservice.dto.EvaluationRequest;
import com.hrapp.leaveevaluationservice.dto.EvaluationResponse;
import com.hrapp.leaveevaluationservice.service.LeaveEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
@RequiredArgsConstructor
public class EvaluationController {

    private final LeaveEvaluationService evaluationService;

    @PostMapping
    public ResponseEntity<EvaluationResponse> evaluate(@Valid @RequestBody EvaluationRequest request) {
        return ResponseEntity.ok(evaluationService.evaluate(request));
    }
}
