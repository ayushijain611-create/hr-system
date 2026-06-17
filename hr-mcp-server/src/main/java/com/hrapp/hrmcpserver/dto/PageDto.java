package com.hrapp.hrmcpserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lean wrapper over Spring's `Page<T>` JSON serialization. Only the fields
 * an LLM cares about are kept; the rest (`pageable`, `sort`, etc.) are
 * intentionally ignored to keep the tool output compact.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageDto<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int number;
    private int size;
    private int numberOfElements;
    private boolean first;
    private boolean last;
}
