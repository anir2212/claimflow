# ClaimFlow

ClaimFlow follows an expense claim from employee submission to manager review and finance payment. The Spring Boot API and React console keep policy decisions, receipt checks and an audit trail together, including when a submission is retried or its text needs a person's correction.

## Why this project

Expense claims become difficult when the same receipt arrives twice, a background job stops, or a reviewer needs to explain a decision later. The project makes those cases visible and recoverable in a local system. Its current receipt input is a text transcription, which keeps the workflow testable while image reading remains open.

## Current milestones

| Area | What works today | Next question |
|---|---|---|
| Claim workflow | Employee, manager and finance routes; fixture JWT roles; database audit history | How should company identity and permissions replace the demo login? |
| Receipt processing | Idempotent submission, duplicate guard, database-backed text job, retries and correction queue | How accurate is actual image OCR on a separately labeled receipt set? |
| Review and operations | Local Chrome role flows, generated OpenAPI contract and job-state metrics endpoint | How will remote CI, accessibility and monitoring be checked? |

## Run locally

You need Java 21, Maven, Node.js 22+; Docker for the MySQL profile.

```sh
./mvnw verify
cd frontend
npm ci
npm test
npm run build
```

Start the API with `./mvnw spring-boot:run` and, in a second terminal, run `cd frontend && npm run dev` to use the local console. The backend exposes OpenAPI at `/v3/api-docs` and a Prometheus-format job count at `/actuator/prometheus`; no monitoring server is bundled. The frontend also has a Chrome end-to-end suite (`npm run test:e2e`). The recorded local checks include 56 passing Maven tests, two Chrome role flows and one bounded MySQL job-recovery run; [results](RESULTS.md) gives their dates and limits.

## Repository map

`src/` is the API and domain logic; `frontend/` contains the review UI; `scripts/` holds MySQL and browser smoke checks.

## Scope

Receipt extraction reads text supplied by the user; image OCR and production identity are outside the current implementation. The async worker uses a database-backed queue, and the default H2 database loses its contents on restart. The MySQL profile supports the bounded persistence checks in [results](RESULTS.md). Demo users and keys are for loopback-only use.
