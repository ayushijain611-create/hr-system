package com.hrapp.employeeservice.service;

import com.hrapp.employeeservice.entity.Employee;
import com.hrapp.employeeservice.exception.ResourceNotFoundException;
import com.hrapp.employeeservice.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {
    private final EmployeeRepository employeeRepository;

    public List<Employee> getAllEmployes() {
        log.info("Fetching all employees");
        return employeeRepository.findAll();
    }

    public Employee getEmployeeById(Long id) {
        log.info("Fetching employee with id: {}", id);
        return employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee","id",id));
    }

    public Employee createEmployee(Employee employee) {
        log.info("Creating new employee: {}", employee.getEmail());
        return employeeRepository.save(employee);
    }

    public Employee updateEmployee(Long id, Employee updatedEmployee) {
        log.info("Updating employee with id: {}", id);
        Employee emp = employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee","id", id));
        emp.setFirstName(updatedEmployee.getFirstName());
        emp.setLastName(updatedEmployee.getLastName());
        emp.setEmail(updatedEmployee.getEmail());
        emp.setDepartment(updatedEmployee.getDepartment());
        emp.setJobTitle(updatedEmployee.getJobTitle());
        emp.setSalary(updatedEmployee.getSalary());
        emp.setStatus(updatedEmployee.getStatus());
        return employeeRepository.save(emp);
    }

    public boolean deleteEmployee(Long id) {
        log.info("Deleting employee with id: {}", id);
        Employee employee = employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee","id",id));
            employeeRepository.delete(employee);
        return true;
    }

    public List<Employee> getEmployeesByDepartment(String department) {
        return employeeRepository.findByDepartment(department);
    }

    public Page<Employee> getAllEmployeesPaginated(int page, int size, String sortBy, String sortDir) {

        // Build sort direction
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        // Build pageable object — this is the magic
        Pageable pageable = PageRequest.of(page, size, sort);

        // Spring handles the SQL automatically
        return employeeRepository.findAll(pageable);
    }
}
