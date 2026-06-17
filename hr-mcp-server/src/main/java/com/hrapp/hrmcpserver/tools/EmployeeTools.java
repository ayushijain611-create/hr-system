package com.hrapp.hrmcpserver.tools;

import com.hrapp.hrmcpserver.client.EmployeeClient;
import com.hrapp.hrmcpserver.dto.EmployeeDto;
import com.hrapp.hrmcpserver.dto.PageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * MCP tool surface for the employee microservice. Read-only by design
 * (employee CRUD writes are intentionally out of scope for this iteration).
 *
 * Every method is {@link Tool}-annotated; the {@code name} attribute is
 * the snake_case identifier the LLM sees, and the {@code description}
 * is the LLM's only contract for what the tool does, when to call it,
 * and what shape comes back. Treat descriptions as production prompt
 * engineering, not afterthought javadoc.
 */
@Component
@Slf4j
public class EmployeeTools {

    private final EmployeeClient employeeClient;

    public EmployeeTools(EmployeeClient employeeClient) {
        this.employeeClient = employeeClient;
    }

    @Tool(
            name = "ping",
            description = "Diagnostic tool. Returns 'pong'. No arguments, no upstream calls, no side effects."
    )
    public String ping() {
        log.info("Tool ping invoked");
        return "pong";
    }

    @Tool(
            name = "get_employee_by_id",
            description = """
                    Fetch a single employee's full record by their numeric employee ID.
                    Returns first/last name, email, department, job title, salary, and
                    employment status (ACTIVE, INACTIVE, or ON_LEAVE).
                    Use this when the user references a specific employee by ID.
                    Raises an error if no employee exists with the given ID.
                    """
    )
    public EmployeeDto getEmployeeById(
            @ToolParam(description = "The employee's numeric ID (positive integer).")
            Long employeeId) {
        log.debug("Tool get_employee_by_id employeeId={}", employeeId);
        return employeeClient.getEmployeeById(employeeId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Employee with id=" + employeeId + " was not found"));
    }

    @Tool(
            name = "list_employees",
            description = """
                    List employees with pagination. Returns a page object with `content`
                    (array of employees) plus pagination metadata (totalElements,
                    totalPages, number, size, first, last). Use this when the user wants
                    to browse the workforce without a specific filter. For department-
                    specific queries prefer `get_employees_by_department` to keep the
                    response focused.
                    """
    )
    public PageDto<EmployeeDto> listEmployees(
            @ToolParam(
                    required = false,
                    description = "Zero-based page number. Defaults to 0 when omitted."
            ) Integer page,
            @ToolParam(
                    required = false,
                    description = "Page size (1-100). Defaults to 10 when omitted; values above 100 are clamped."
            ) Integer size,
            @ToolParam(
                    required = false,
                    description = "Field to sort by. One of: id, firstName, lastName, email, department, jobTitle, salary, status. Defaults to id."
            ) String sortBy,
            @ToolParam(
                    required = false,
                    description = "Sort direction: asc or desc. Defaults to asc."
            ) String sortDir) {
        int resolvedPage = page == null ? 0 : Math.max(0, page);
        int resolvedSize = size == null ? 10 : Math.min(100, Math.max(1, size));
        String resolvedSortBy = (sortBy == null || sortBy.isBlank()) ? "id" : sortBy.trim();
        String resolvedSortDir = (sortDir == null || sortDir.isBlank()) ? "asc" : sortDir.trim();
        log.debug("Tool list_employees page={} size={} sortBy={} sortDir={}",
                resolvedPage, resolvedSize, resolvedSortBy, resolvedSortDir);
        return employeeClient.listEmployees(resolvedPage, resolvedSize, resolvedSortBy, resolvedSortDir);
    }

    @Tool(
            name = "get_employees_by_department",
            description = """
                    Return every employee in a specific department (e.g. 'Engineering',
                    'HR', 'Finance', 'Marketing'). The match is case-sensitive against
                    the department string stored in the system. Returns an empty list
                    if no employees are found. There is no pagination on this endpoint;
                    if a department is very large prefer `list_employees` with a sort/filter
                    workflow instead.
                    """
    )
    public List<EmployeeDto> getEmployeesByDepartment(
            @ToolParam(description = "Exact department name to filter by (case-sensitive).")
            String department) {
        log.debug("Tool get_employees_by_department department={}", department);
        return employeeClient.getEmployeesByDepartment(department);
    }
}
