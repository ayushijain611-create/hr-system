package com.hrapp.leaveevaluationservice.controller;

import com.hrapp.leaveevaluationservice.service.PolicyIngestionService;
import com.hrapp.leaveevaluationservice.service.PolicyIngestionService.IngestResult;
import com.hrapp.leaveevaluationservice.service.PolicyIngestionService.PolicySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyIngestionService ingestionService;

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResult> ingest(@RequestPart("file") MultipartFile file) {
        IngestResult result = ingestionService.ingest(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<PolicySummary>> list() {
        return ResponseEntity.ok(ingestionService.listSources());
    }

    @DeleteMapping("/{source}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String source) {
        long deleted = ingestionService.deleteBySource(source);
        return ResponseEntity.ok(Map.of(
                "source", source,
                "deletedChunks", deleted
        ));
    }
}
