# ClaimFlow

ClaimFlow follows an expense claim from employee submission to manager review and finance payment. The Spring Boot API and React console keep policy decisions, receipt checks and an audit trail together, including when a submission is retried or its text needs a person's correction.

## Why this project

Expense claims become difficult when the same receipt arrives twice, a background job stops, or a reviewer needs to explain a decision later. The project makes those cases visible and recoverable in a local system. Its working receipt input is a text transcription. An image endpoint now feeds OCR text through the same rules, but no OCR engine is bundled, so image reading itself remains open.

## Current milestones

| Area | What works today | Next question |
|---|---|---|
| Claim workflow | Employee, manager and finance routes; fixture JWT roles; database audit history | How should company identity and permissions replace the demo login? |
| Receipt processing | Idempotent submission, duplicate guard, database-backed text job, retries and correction queue | How accurate is actual image OCR on a separately labeled receipt set? |
| Receipt image seam and benchmark contract | `POST /api/claims/image` validates PNG/JPEG bytes and passes OCR text through the existing job, parser, amount, duplicate and correction rules behind an `OcrAdapter` interface. A labelled-receipt manifest validator and field-accuracy scorer (`dev.claimflow.benchmark`) are tested on a synthetic fixture | No OCR engine is bundled (the endpoint returns 503), so no OCR accuracy is measured. The next step is an authorized, human-labelled receipt set and a real adapter scored on it |
| Receipt upload in the console | Since 24 October an employee can attach a PNG or JPEG receipt (5 MiB). The browser checks size, type and leading bytes, shows progress and explains 400, 413, 503 and network failures; each claim records whether its receipt came as text or an image | With no OCR engine the server answers 503 and no claim is created; uploaded image bytes are not stored |
| Multi-instance state changes | Decisions, payments, receipt corrections and extraction lock the claim row (`SELECT … FOR UPDATE`) with a version column as a backstop, and conflicts return 409. A pre-registered two-instance check against one MySQL found that, before the fix, 22 of 40 claims accepted two or three decisions and 15 of 22 approved claims were paid more than once; after it, every criterion passed | One run of one workload on one laptop; crashes, database restarts and sustained load are untested |
| Review and operations | Local Chrome role flows (3/3), generated OpenAPI contract, claim-transition, decision-latency and upload metrics with six Prometheus alert rules tested by promtool (10/10). GitHub Actions runs the backend, frontend and browser jobs on every pull request, plus an alert-rule job | Thresholds are untuned, there is no Alertmanager route, and no alert has fired outside test fixtures; accessibility is unchecked |

## Run locally

You need Java 21, Maven, Node.js 22+; Docker for the MySQL profile.

```sh
./mvnw verify
cd frontend
npm ci
npm test
npm run build
```

Start the API with `./mvnw spring-boot:run` and, in a second terminal, run `cd frontend && npm run dev` to use the local console. The backend exposes OpenAPI at `/v3/api-docs` and Prometheus metrics at `/actuator/prometheus`: job counts, `claimflow_claim_transitions_total`, `claimflow_approval_latency_seconds` and `claimflow_receipt_uploads_total`, recorded only after the database commit. `ops/prometheus/` holds the alert rules, their promtool tests and scrape configs; `docker compose --profile monitoring up` adds a local Prometheus on 127.0.0.1:19090. `scripts/multiworker_check.py` runs the two-instance check described in [its protocol](docs/MULTI_WORKER_PROTOCOL.md), which was committed before the runs. The frontend also has a Chrome end-to-end suite (`npm run test:e2e`). The recorded local checks include 77 passing Maven tests and 10 Vitest tests (24 October), three Chrome role flows, the two-instance locking check and one bounded MySQL job-recovery run; [results](RESULTS.md) gives their dates and limits.

## Repository map

`src/` is the API and domain logic; `frontend/` contains the review UI; `scripts/` holds MySQL and browser smoke checks.

## Scope

Receipt extraction reads text supplied by the user. The image endpoint and console upload have no OCR engine behind them, and production identity is outside the current implementation. The async worker uses a database-backed queue, and the default H2 database loses its contents on restart. The MySQL profile supports the bounded persistence checks in [results](RESULTS.md). Demo users and keys are for loopback-only use.
