package com.hrapp.employeeservice;

import com.hrapp.employeeservice.entity.Employee;
import com.hrapp.employeeservice.exception.ResourceNotFoundException;
import com.hrapp.employeeservice.repository.EmployeeRepository;
import com.hrapp.employeeservice.service.EmployeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceApplicationTests {

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private EmployeeService employeeService;

    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = Employee.builder()
                .firstName("Jane")
                .lastName("Doe")
                .email("jane@example.com")
                .department("Engineering")
                .jobTitle("Engineer")
                .salary(80000.0)
                .status(Employee.EmployeeStatus.ACTIVE)
                .build();
    }

    // ── createEmployee ────────────────────────────────────────────────────────

    @Test
    void createEmployee_validEmployee_returnsSavedEmployee() {
        when(employeeRepository.save(employee)).thenReturn(employee);

        Employee result = employeeService.createEmployee(employee);

        assertThat(result).isEqualTo(employee);
        verify(employeeRepository).save(employee);
    }

    // ── getEmployeeById ───────────────────────────────────────────────────────

    @Test
    void getEmployeeById_existingId_returnsEmployee() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));

        Employee result = employeeService.getEmployeeById(1L);

        assertThat(result).isEqualTo(employee);
    }

    @Test
    void getEmployeeById_unknownId_throwsResourceNotFoundException() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employeeService.getEmployeeById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── updateEmployee ────────────────────────────────────────────────────────

    @Test
    void updateEmployee_existingId_updatesAllFields() {
        Employee updated = Employee.builder()
                .firstName("John").lastName("Smith")
                .email("john@example.com").department("HR")
                .jobTitle("Manager").salary(95000.0)
                .status(Employee.EmployeeStatus.INACTIVE)
                .build();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(employeeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Employee result = employeeService.updateEmployee(1L, updated);

        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");
        assertThat(result.getEmail()).isEqualTo("john@example.com");
        assertThat(result.getDepartment()).isEqualTo("HR");
        assertThat(result.getJobTitle()).isEqualTo("Manager");
        assertThat(result.getSalary()).isEqualTo(95000.0);
        assertThat(result.getStatus()).isEqualTo(Employee.EmployeeStatus.INACTIVE);
    }

    @Test
    void updateEmployee_unknownId_throwsResourceNotFoundException() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employeeService.updateEmployee(99L, employee))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(employeeRepository, never()).save(any());
    }

    // ── deleteEmployee ────────────────────────────────────────────────────────

    @Test
    void deleteEmployee_existingId_deletesEmployee() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));

        employeeService.deleteEmployee(1L);

        verify(employeeRepository).delete(employee);
    }

    @Test
    void deleteEmployee_unknownId_throwsResourceNotFoundException() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employeeService.deleteEmployee(99L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(employeeRepository, never()).delete(any());
    }

    // ── getEmployeesByDepartment — parameterized ───────────────────────────────
    // Streams (department, expectedResultCount) pairs to verify both the
    // "found" and "not found" paths from a single test method.

    static Stream<Arguments> departmentScenarios() {
        return Stream.of(
                Arguments.of("Engineering", 1),
                Arguments.of("Finance",     0)
        );
    }

    @ParameterizedTest(name = "department={0} → {1} result(s)")
    @MethodSource("departmentScenarios")
    void getEmployeesByDepartment_returnsExpectedCount(String department, int expectedCount) {
        List<Employee> mockResult = expectedCount > 0 ? List.of(employee) : List.of();
        when(employeeRepository.findByDepartment(department)).thenReturn(mockResult);

        List<Employee> result = employeeService.getEmployeesByDepartment(department);

        assertThat(result).hasSize(expectedCount);
    }

    // ── getAllEmployeesPaginated — valid sort fields (parameterized) ────────────
    // Streams every allowed field × both sort directions to assert none throws.

    static Stream<Arguments> validSortFieldScenarios() {
        List<String> fields = List.of(
                "id", "firstName", "lastName", "email",
                "department", "jobTitle", "salary", "status"
        );
        List<String> directions = List.of("asc", "desc");
        return fields.stream()
                .flatMap(field -> directions.stream()
                        .map(dir -> Arguments.of(field, dir)));
    }

    @ParameterizedTest(name = "sortBy={0}, dir={1} → no exception")
    @MethodSource("validSortFieldScenarios")
    void getAllEmployeesPaginated_validSortField_doesNotThrow(String sortBy, String sortDir) {
        when(employeeRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThatCode(() ->
                employeeService.getAllEmployeesPaginated(0, 10, sortBy, sortDir))
                .doesNotThrowAnyException();
    }

    // ── getAllEmployeesPaginated — invalid sort fields (parameterized) ──────────
    // Streams bad inputs to assert each one throws IllegalArgumentException
    // containing the offending field name in the message.

    static Stream<Arguments> invalidSortFieldScenarios() {
        return Stream.of(
                Arguments.of("nonExistentField"),
                Arguments.of("NAME"),           // wrong case — "firstName" is correct
                Arguments.of("Id"),             // old wrong-cased field name from before the fix
                Arguments.of("salary;drop"),    // injection attempt
                Arguments.of("")               // blank
        );
    }

    @ParameterizedTest(name = "sortBy=\"{0}\" → IllegalArgumentException")
    @MethodSource("invalidSortFieldScenarios")
    void getAllEmployeesPaginated_invalidSortField_throwsIllegalArgumentException(String sortBy) {
        assertThatThrownBy(() ->
                employeeService.getAllEmployeesPaginated(0, 10, sortBy, "asc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(sortBy);

        verify(employeeRepository, never()).findAll(any(Pageable.class));
    }

    // ── getAllEmployeesPaginated — happy path ─────────────────────────────────

    @Test
    void getAllEmployeesPaginated_validRequest_returnsPage() {
        Page<Employee> page = new PageImpl<>(List.of(employee));
        when(employeeRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<Employee> result = employeeService.getAllEmployeesPaginated(0, 10, "firstName", "asc");

        assertThat(result.getContent()).containsExactly(employee);
    }
}
