package com.hrapp.employeeservice;

import com.hrapp.employeeservice.entity.Employee;
import com.hrapp.employeeservice.exception.ResourceNotFoundException;
import com.hrapp.employeeservice.repository.EmployeeRepository;
import com.hrapp.employeeservice.service.EmployeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

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
                .firstName("John")
                .lastName("Smith")
                .email("john@example.com")
                .department("HR")
                .jobTitle("Manager")
                .salary(95000.0)
                .status(Employee.EmployeeStatus.INACTIVE)
                .build();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));

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

    // ── getEmployeesByDepartment ──────────────────────────────────────────────

    @Test
    void getEmployeesByDepartment_returnsMatchingEmployees() {
        when(employeeRepository.findByDepartment("Engineering"))
                .thenReturn(List.of(employee));

        List<Employee> result = employeeService.getEmployeesByDepartment("Engineering");

        assertThat(result).hasSize(1).containsExactly(employee);
    }

    @Test
    void getEmployeesByDepartment_noneFound_returnsEmptyList() {
        when(employeeRepository.findByDepartment("Finance")).thenReturn(List.of());

        List<Employee> result = employeeService.getEmployeesByDepartment("Finance");

        assertThat(result).isEmpty();
    }

    // ── getAllEmployeesPaginated ───────────────────────────────────────────────

    @Test
    void getAllEmployeesPaginated_validSortField_returnsPage() {
        Page<Employee> page = new PageImpl<>(List.of(employee));
        when(employeeRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<Employee> result = employeeService.getAllEmployeesPaginated(0, 10, "firstName", "asc");

        assertThat(result.getContent()).containsExactly(employee);
        verify(employeeRepository).findAll(any(Pageable.class));
    }

    @Test
    void getAllEmployeesPaginated_descSort_returnsPage() {
        Page<Employee> page = new PageImpl<>(List.of(employee));
        when(employeeRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<Employee> result = employeeService.getAllEmployeesPaginated(0, 10, "salary", "desc");

        assertThat(result.getContent()).containsExactly(employee);
    }

    @Test
    void getAllEmployeesPaginated_invalidSortField_throwsIllegalArgumentException() {
        assertThatThrownBy(() ->
                employeeService.getAllEmployeesPaginated(0, 10, "nonExistentField", "asc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonExistentField");

        verify(employeeRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void getAllEmployeesPaginated_allAllowedSortFields_doNotThrow() {
        Page<Employee> page = new PageImpl<>(List.of());
        when(employeeRepository.findAll(any(Pageable.class))).thenReturn(page);

        for (String field : List.of("id", "firstName", "lastName", "email",
                "department", "jobTitle", "salary", "status")) {
            assertThatCode(() ->
                    employeeService.getAllEmployeesPaginated(0, 10, field, "asc"))
                    .doesNotThrowAnyException();
        }
    }
}
