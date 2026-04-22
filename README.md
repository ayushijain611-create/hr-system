# HR Management System

A production-ready microservices-based HR Management System 
built with Spring Boot, Docker, and PostgreSQL.

## Architecture

```
Employee Service (port 8080) ←→ Leave Service (port 8081)
        │                               │
   PostgreSQL                      PostgreSQL
   (port 5432)                     (port 5433)
```

## Tech Stack

- **Java 17** — core language
- **Spring Boot 3.5** — microservices framework
- **Spring Data JPA + Hibernate** — database ORM
- **PostgreSQL** — relational database
- **Docker** — containerisation
- **Lombok** — boilerplate reduction
- **Maven** — build tool

## Microservices

### Employee Service (port 8080)
Manages employee profiles with full CRUD operations.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/employees | Get all employees (paginated) |
| GET | /api/employees/{id} | Get employee by ID |
| POST | /api/employees | Create employee |
| PUT | /api/employees/{id} | Update employee |
| DELETE | /api/employees/{id} | Delete employee |
| GET | /api/employees/department/{dept} | Get by department |

### Leave Service (port 8081)
Manages employee leave requests with approval workflow.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/leaves | Apply for leave |
| GET | /api/leaves | Get all leaves (paginated) |
| GET | /api/leaves/{id} | Get leave by ID |
| GET | /api/leaves/employee/{id} | Get leaves by employee |
| PUT | /api/leaves/{id}/approve | Approve leave |
| PUT | /api/leaves/{id}/reject | Reject leave |
| PUT | /api/leaves/{id}/cancel | Cancel leave |

## Key Features

- ✅ RESTful APIs with proper HTTP status codes
- ✅ Pagination and sorting on all list endpoints
- ✅ Input validation with meaningful error messages
- ✅ Global exception handling
- ✅ Cross-service communication via REST
- ✅ Database per service (microservices best practice)
- ✅ Structured logging

## Running Locally

### Prerequisites
- Java 17
- Docker Desktop
- Maven

### 1. Start databases
```bash
# Employee database
docker run --name hr-postgres \
  -e POSTGRES_DB=employeedb \
  -e POSTGRES_USER=hruser \
  -e POSTGRES_PASSWORD=hrpassword \
  -p 5432:5432 -d postgres:15

# Leave database
docker run --name leave-postgres \
  -e POSTGRES_DB=leavedb \
  -e POSTGRES_USER=leaveuser \
  -e POSTGRES_PASSWORD=leavepassword \
  -p 5433:5432 -d postgres:15
```

### 2. Start Employee Service
```bash
cd employee-service
./mvnw spring-boot:run
# Runs on http://localhost:8080
```

### 3. Start Leave Service
```bash
cd leave-service
./mvnw spring-boot:run
# Runs on http://localhost:8081
```

## Coming Soon
- 🔄 Kafka event streaming between services
- 🐳 Docker Compose for one-command startup
- ☸️ Kubernetes deployment with Minikube
- 🤖 AI-powered HR assistant using Anthropic Claude
