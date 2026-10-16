# ClaimFlow

Expense claims with policy checks and an auditable review flow.

A Spring Boot expense-claim workflow with role checks, idempotent submission, policy evaluation, an auditable text-receipt extraction queue, and a React review UI.

## Why this project

Expense claims are routine until a receipt is ambiguous, a policy is breached, or a submission is retried. This project explores how to keep those cases auditable while allowing employees, managers, and finance to move a claim through one consistent workflow.

## Run locally

You need Java 21, Maven, Node.js 22+; Docker for the MySQL profile.

```sh
./mvnw verify
cd frontend
npm ci
npm test
npm run build
```

The backend exposes OpenAPI at `/v3/api-docs` and Prometheus metrics at `/actuator/prometheus`. The frontend also has a Chrome end-to-end suite (`npm run test:e2e`). See [results](RESULTS.md) for the verified test matrix.

## Repository map

`src/` is the API and domain logic; `frontend/` contains the review UI; `scripts/` holds MySQL and browser smoke checks.

## Scope

Receipt extraction reads text supplied by the user; image OCR and production identity are outside the current implementation. The async worker uses a database-backed queue.
