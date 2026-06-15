# HR Management System

A microservices-based HR Management System built with Spring Boot,
Docker, PostgreSQL, and a RAG-powered leave evaluation service that uses
Spring AI + Ollama (local LLM) + PGVector.

## Architecture

```
                         ┌──────────────────────────┐
                         │   Employee Service       │  ──► hr-postgres :5432
                         │   :8080                  │
                         └────────────▲─────────────┘
                                      │
                  ┌───────────────────┴──────────────────┐
                  │                                      │
       ┌──────────┴────────────┐         ┌───────────────┴────────────────┐
       │   Leave Service       │         │   Leave Evaluation Service     │
       │   :8081               │ ──────► │   :8082    (RAG over policies) │
       │   → leave-postgres    │ POST    │   → leave-eval-postgres :5434  │
       │     :5433             │  /eval  │     (Postgres + pgvector)      │
       └───────────────────────┘         │   → ollama :11434 (LLM)        │
                                         └────────────────────────────────┘
```

`leave-service` calls `leave-evaluation-service` synchronously when a
leave is applied. The AI recommendation is stored on the `LeaveRequest`
(`aiOutcome`, `aiConfidenceScore`, `aiReasons`) but the leave's `status`
stays `PENDING` — a human manager still makes the final call. If the
evaluation service is unavailable the leave is still created; the AI
fields are simply left `null`.

## Tech Stack

- **Java 17** + **Spring Boot 3.5.13**
- **Spring Data JPA + Hibernate** — relational ORM
- **Spring AI 1.1.7** — LLM client + RAG primitives
  - `spring-ai-starter-model-ollama`
  - `spring-ai-starter-vector-store-pgvector`
  - `spring-ai-pdf-document-reader` (Apache PDFBox)
- **Ollama** — local LLM runtime
  - `llama3.1:8b` for chat
  - `nomic-embed-text` (768-dim) for embeddings
- **PostgreSQL 15** for transactional services, **pgvector/pgvector:pg16**
  for the vector store
- **Docker + Docker Compose** — one-command startup
- **Lombok**, **Maven**, **JUnit 5 + Mockito + AssertJ**

## Microservices

### Employee Service (port 8080)
Manages employee profiles with full CRUD operations.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/employees` | Get all employees (paginated) |
| GET | `/api/employees/{id}` | Get employee by ID |
| POST | `/api/employees` | Create employee |
| PUT | `/api/employees/{id}` | Update employee |
| DELETE | `/api/employees/{id}` | Delete employee |
| GET | `/api/employees/department/{dept}` | Get by department |

### Leave Service (port 8081)
Manages employee leave requests; calls the evaluation service synchronously at apply-time.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/leaves` | Apply for leave (triggers AI evaluation) |
| GET | `/api/leaves` | Get all leaves (paginated) |
| GET | `/api/leaves/{id}` | Get leave by ID |
| GET | `/api/leaves/employee/{id}` | Get leaves by employee |
| GET | `/api/leaves/search?startDate=&endDate=&status=&excludeEmployeeId=` | Find overlapping leaves in a date window |
| PUT | `/api/leaves/{id}/approve` | Approve leave |
| PUT | `/api/leaves/{id}/reject` | Reject leave |
| PUT | `/api/leaves/{id}/cancel` | Cancel leave |

The `LeaveRequest` payload now includes three AI fields (nullable):
- `aiOutcome` — `APPROVE` or `REJECT`
- `aiConfidenceScore` — `0.0`–`1.0`
- `aiReasons` — list of short, policy-grounded strings

### Leave Evaluation Service (port 8082)
RAG-powered policy evaluator. Stores company policies as embeddings in
PGVector, retrieves the most relevant chunks for an incoming leave
request, and asks a local Ollama LLM for a structured recommendation.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/policies/ingest` | Upload a PDF policy (`multipart/form-data`, field name `file`). Idempotent: prior chunks for the same filename are replaced |
| GET | `/api/policies` | List ingested policies (source filename + chunk count) |
| DELETE | `/api/policies/{source}` | Remove all chunks for a policy by filename |
| POST | `/api/evaluations` | Evaluate a leave request; returns `{outcome, confidenceScore, reasons, policySourcesUsed}` |

## Running with Docker Compose (one-command startup)

### Prerequisites
- Docker Desktop (with enough disk for the ~5GB Ollama models)
- For local development: Java 17 + Maven

### Start everything
```bash
docker-compose up -d
```

This brings up: three Postgres instances, Ollama, a one-shot
`ollama-model-init` container that pulls `nomic-embed-text` and
`llama3.1:8b`, and all three Spring Boot services.

**First-run note:** the model pull takes several minutes (~5GB). The
evaluation service waits for `ollama-model-init` to complete
(`condition: service_completed_successfully`) before starting, so it
will boot quickly on subsequent runs from cached models.

### Ingest the sample policies
After all services are up:
```bash
curl -X POST http://localhost:8082/api/policies/ingest \
     -F "file=@./policies/Employee Leave Policy.pdf"
curl -X POST http://localhost:8082/api/policies/ingest \
     -F "file=@./policies/Project Delivery and Release Management Policy.pdf"
curl -X POST http://localhost:8082/api/policies/ingest \
     -F "file=@./policies/Team Staffing and SLA Policy.pdf"

curl http://localhost:8082/api/policies
```

### Try an AI-evaluated leave
```bash
curl -X POST http://localhost:8081/api/leaves \
     -H "Content-Type: application/json" \
     -d '{
           "employeeId": 1,
           "leaveType": "ANNUAL",
           "startDate": "2026-08-10",
           "endDate": "2026-08-12",
           "reason": "Weekend trip"
         }'
```
The response includes the populated `aiOutcome`, `aiConfidenceScore`,
and `aiReasons` fields. Status remains `PENDING`.

## Running Locally (without Docker Compose)

Useful for development on a single service.

```bash
# Start Postgres + pgvector + Ollama
docker run -d --name hr-postgres -e POSTGRES_DB=employeedb \
  -e POSTGRES_USER=hruser -e POSTGRES_PASSWORD=hrpassword \
  -p 5432:5432 postgres:15
docker run -d --name leave-postgres -e POSTGRES_DB=leavedb \
  -e POSTGRES_USER=leaveuser -e POSTGRES_PASSWORD=leavepassword \
  -p 5433:5432 postgres:15
docker run -d --name leave-eval-postgres -e POSTGRES_DB=leaveevaldb \
  -e POSTGRES_USER=evaluser -e POSTGRES_PASSWORD=evalpassword \
  -p 5434:5432 pgvector/pgvector:pg16
docker run -d --name ollama -p 11434:11434 \
  -v ollama-data:/root/.ollama ollama/ollama:latest
docker exec ollama ollama pull nomic-embed-text
docker exec ollama ollama pull llama3.1:8b

# Start services (each in its own terminal)
cd employee-service && ./mvnw spring-boot:run            # :8080
cd leave-evaluation-service && ./mvnw spring-boot:run    # :8082
cd leave-service && ./mvnw spring-boot:run               # :8081
```

## Tests

The evaluation service ships with parameterized JUnit 5 tests covering
happy paths, edge cases, and invalid inputs for the RAG service:

```bash
cd leave-evaluation-service && ./mvnw test
```

Currently 31 tests, covering: end-to-end evaluation scenarios,
confidence-score clamping (NaN / negative / >1 / infinity), source
deduplication and ordering, missing-context degradation, prompt
truncation of long history, retrieval `topK` and query construction,
null/exception paths from the LLM, and edge-case request fields
(null reason, single-day leave, etc.).

## Key Features

- RESTful APIs with proper HTTP status codes
- Pagination and sorting on list endpoints
- Input validation with structured error responses
- Global exception handling per service
- Cross-service communication via Spring's `RestClient`
- Database-per-service (microservices best practice)
- **RAG over company policies** with idempotent PDF ingestion
- **Synchronous AI evaluation** at leave-application time with graceful
  degradation when the AI service or LLM is unavailable
- Structured logging

## Notes / Limitations

- The policy ingestion endpoint (`POST /api/policies/ingest`) is
  currently unauthenticated — intended for admin use only.
- AI recommendations are advisory; nothing changes leave status
  automatically. A deterministic rule layer is planned for a future
  iteration to complement the LLM output.
- On first startup the Ollama model pull takes several minutes (~5GB
  total). Subsequent starts are fast.
