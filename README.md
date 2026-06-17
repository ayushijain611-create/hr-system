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

A fourth optional component, `hr-mcp-server`, wraps the three services
and exposes a curated subset of their operations to AI agents
(Claude Desktop, Cursor, ChatGPT desktop, etc.) over the Model Context
Protocol:

```
   ┌──────────────────────────────┐
   │  AI agent                    │
   │  Claude Desktop / Cursor /   │
   │  ChatGPT desktop             │
   └──────────────┬───────────────┘
                  │ MCP over STDIO (JSON-RPC)
                  ▼
   ┌──────────────────────────────┐
   │  hr-mcp-server (JVM)         │ ──► employee-service       :8080
   │  spring-ai-starter-mcp-      │ ──► leave-service          :8081
   │    server                    │ ──► leave-evaluation-svc   :8082
   └──────────────────────────────┘
```

## Tech Stack

- **Java 17** + **Spring Boot 3.5.13**
- **Spring Data JPA + Hibernate** — relational ORM
- **Spring AI 1.1.7 / 1.1.8** — LLM client + RAG primitives + MCP server
  - `spring-ai-starter-model-ollama`
  - `spring-ai-starter-vector-store-pgvector`
  - `spring-ai-pdf-document-reader` (Apache PDFBox)
  - `spring-ai-starter-mcp-server` (STDIO transport, hr-mcp-server only)
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

## MCP Server (`hr-mcp-server`) — HR tools for AI agents

An optional Spring Boot module that re-exposes the three HR services as
[Model Context Protocol](https://modelcontextprotocol.io) tools over
**STDIO**. Drop the jar into any MCP-aware client and your AI agent can
look up employees, manage leave requests, and run policy-aware leave
evaluations as first-class tool calls.

### Tools exposed (12)

| Upstream service | Tool | Effect |
|---|---|---|
| employee | `get_employee_by_id` | read |
| employee | `list_employees` | read, paginated |
| employee | `get_employees_by_department` | read |
| leave | `get_leave_by_id` | read |
| leave | `list_leaves` | read, paginated |
| leave | `get_leaves_by_employee` | read |
| leave | `search_leaves` | read, by date-window + optional status |
| leave | `approve_leave` | state change `PENDING → APPROVED` |
| leave | `reject_leave` | state change `PENDING → REJECTED` |
| leave | `cancel_leave` | state change `PENDING → CANCELLED` |
| leave-evaluation | `evaluate_leave_request` | advisory only; no state change |
| internal | `ping` | diagnostic, no upstream call |

**Intentional omissions:** leave application (`POST /api/leaves`),
employee CRUD writes, and policy ingestion are not exposed. Filing
leaves and rewriting policies are admin operations that should not flow
through an LLM.

### Build

```bash
cd hr-mcp-server
mvn -DskipTests package
```

Produces `target/hr-mcp-server-0.0.1-SNAPSHOT.jar` (~28 MB, fat jar).
The server is **headless** — no embedded Tomcat, no HTTP port, stdout is
reserved for the MCP JSON-RPC stream.

### Smoke test

Requires `employee-service`, `leave-service`, and
`leave-evaluation-service` to be reachable on `localhost:8080/8081/8082`
(via Docker Compose or running them locally), and at least one ingested
policy in the evaluation service.

```powershell
powershell -ExecutionPolicy Bypass `
  -File hr-mcp-server\scripts\mcp-smoke.ps1
```

The script seeds a fresh `PENDING` leave on `leave-service`, spawns the
MCP server as a child process, and walks it through `initialize` →
`tools/list` → five `tools/call` invocations covering one tool from each
category. A green run:

```
[1] initialize         OK    protocolVersion=2024-11-05 server=hr-mcp-server/0.0.1
[2] tools/list         OK    advertised=12 expected=12
[3] ping               OK    result='"pong"'
[4] list_employees     OK    returned=3 total=4
[5] evaluate_leave     OK    outcome=APPROVE confidence=0.85 sources=[Employee Leave Policy.pdf; ...]
[6] get_leave_by_id    OK    id=23 status=PENDING totalDays=2
[7] cancel_leave       OK    id=23 status=CANCELLED
Smoke PASSED
```

### Wiring into an MCP client

The same JSON snippet works for every MCP client — only the
configuration file location changes. Adjust the absolute path to the
jar for your machine.

#### Claude Desktop

Edit `claude_desktop_config.json`:

- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "hr": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\Projects\\spring-boot\\Claude\\hr-systems\\hr-mcp-server\\target\\hr-mcp-server-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "EMPLOYEE_SERVICE_URL":   "http://localhost:8080",
        "LEAVE_SERVICE_URL":      "http://localhost:8081",
        "EVALUATION_SERVICE_URL": "http://localhost:8082"
      }
    }
  }
}
```

Restart Claude Desktop; the 11 user-facing tools (everything except
`ping`) appear in the tools menu.

#### Cursor

Project-scoped (`.cursor/mcp.json` in the repo root) or user-scoped
(`~/.cursor/mcp.json`) — same JSON shape as above.

#### ChatGPT desktop & other clients

Any MCP client that supports STDIO servers can use the same
`command` / `args` / `env` triple. Consult the client's documentation
for the exact config path.

### Logs

The MCP server cannot write to stdout (the MCP client owns that pipe
for JSON-RPC framing) so Logback is configured to skip the console
appender entirely and route everything to a rolling file:

```
%USERPROFILE%\.hr-mcp-server\hr-mcp-server.log    (Windows)
$HOME/.hr-mcp-server/hr-mcp-server.log            (macOS/Linux)
```

Each tool invocation logs at `INFO` level (`Tool list_employees invoked
page=0 size=3`, etc.); upstream HTTP errors propagate to the LLM as
tool errors instead of being swallowed.

### Limitations / future work

- **STDIO only.** One client process per JVM. Each connected agent
  spawns its own server. Switching to Streamable HTTP transport (one
  shared JVM, many concurrent clients) is the obvious next step and
  was scoped out of this iteration only to keep the deployment
  surface minimal.
- **Sequential tools/call recommended.** The MCP Java SDK uses a
  non-thread-safe Reactor sink for outbound framing, so issuing
  parallel `tools/call` requests against a single STDIO session can
  occasionally drop responses with `Failed to enqueue message`. All
  major MCP clients (Claude Desktop, Cursor, ChatGPT) already drive
  servers sequentially, so this is not a practical concern, but
  custom clients should follow the same pattern.
- **No authentication.** The server inherits whatever access the
  spawning OS user already has to the three upstream services. When
  we move to HTTP transport, OAuth / API-key middleware becomes a
  hard requirement.

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
- **MCP server (STDIO)** that exposes 11 read/write HR tools to AI
  agents like Claude Desktop, Cursor, and ChatGPT
- Structured logging

## Notes / Limitations

- The policy ingestion endpoint (`POST /api/policies/ingest`) is
  currently unauthenticated — intended for admin use only.
- AI recommendations are advisory; nothing changes leave status
  automatically. A deterministic rule layer is planned for a future
  iteration to complement the LLM output.
- On first startup the Ollama model pull takes several minutes (~5GB
  total). Subsequent starts are fast.
