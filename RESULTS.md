# ClaimFlow measured results

## Receipt upload, metrics and alert rules, and two-instance locking, 24 October 2026

All runs used an Apple M3 Pro (11 cores, 18 GiB, macOS 15.3.1) shared with other jobs, Java 21 and Node 26.10. Timings are descriptive, not benchmarks.

**Test suites.** All counts are from the final run after the last code change.

| Suite | Before (003a6c4) | After |
|---|---|---|
| `./mvnw -o -q verify` (JaCoCo gate passed) | 70 passed, 0 failed, 0 skipped | **77 passed, 0 failed, 0 skipped** |
| `npm test` (Vitest) | 2/2 | **10/10** |
| `npm run build` | passed | passed, 156.60 kB JavaScript (50.70 kB gzip) |
| `npm run test:e2e` (installed Chrome, one worker) | 2/2 on 16 October | **3/3 in 1.2 minutes** |

The new backend tests are `ClaimMetricsIntegrationTest` (3), `ConcurrentDecisionIntegrationTest` (3) and one image-source visibility test; one existing test was extended. JaCoCo line coverage: ClaimService 116/126 (92.1%), ClaimMetrics 40/40, ClaimTransitionListener 5/5, ReceiptImageIntake 31/35 (88.6%), ExtractionProcessor 24/27 (88.9%).

**Receipt upload.** Vitest covers the client checks: empty, oversized, wrong-type, non-image and mislabelled files are rejected without a request. It also covers a mocked successful upload with a 50% progress state, mocked 503, 413 and network failures, and the image-source label in the manager and finance views. The new Chrome flow ran against the real H2 API with no OCR engine. A `.txt` file and a 6 MiB PNG were refused in the browser. A 12-byte PNG reached the server, which answered **HTTP 503**, and the console reported that no claim was created; the employee's claim count did not change. A backend test with the scripted test adapter showed `receiptSource: IMAGE` in the owner's list, the manager queue and the finance list, and a second employee received 403. **No OCR ran and no image claim was created through a real engine.**

**Alert rules.** Using the cached `prom/prometheus` image (promtool 3.15.0, no download), `promtool check rules alerts.yml` found **6 rules, SUCCESS**. `promtool test rules alerts_test.yml` passed **10 of 10 test groups**, one firing and at least one quiet case per rule. To check that the fixtures can fail, six edits were made one at a time to a scratch copy: three thresholds, one ratio, one `for` duration and one offset. Each made the suite fail (6/6). These are synthetic series. **No alert has fired in any deployment.**

**Two-instance locking (protocol in [docs/MULTI_WORKER_PROTOCOL.md](docs/MULTI_WORKER_PROTOCOL.md), committed in `f760fd9` before either run).** Set-up: a disposable MySQL 8.4 from the cached image (`docker compose -p p06check`, fresh volume, since removed) and two host-run JARs on ports 18081 and 18082 with both extraction pollers on. Each run made 40 synchronous claims, raced 4 decisions per claim (approve and reject on each instance) with 40 requests in flight, raced 4 payments per approved claim, then shared 60 async jobs between the two pollers.

| Criterion | `baseline` (JAR from `3998895`, no claim lock) | `fixed` (row lock + V6 version) |
|---|---|---|
| D1: one 2xx and three 409s per claim | **fail**: 30/40 claims failed; 22 accepted two or three decisions | pass, 40/40 (160 requests: 40 × 200, 120 × 409) |
| D2: final state matches the winner | fail, 22/40 | pass |
| D3: one decision audit event | fail, 22/40; up to 3 on one claim | pass, exactly 1 each |
| P1: one payment per approved claim | **fail**: 15 of 22 approved claims; up to 3 `PAID` events on one claim | pass, 21/21 (84 requests: 21 × 200, 63 × 409) |
| E1: 60 async claims extracted once each | pass, 60/60 in 3.6 s | pass, 60/60 in 6.8 s |
| G1: no 5xx or transport error | passed as written, but see below | pass; server logs had no ERROR lines |

The baseline's decision statuses were 64 × 200, 73 × 409 and **23 × 403**; its payment statuses were 37 × 200, 37 × 409 and 14 × 403. The server logs show that all 37 of the 403s were MySQL deadlock victims (`CannotAcquireLockException`). Each transaction's audit insert took a shared lock on the claim row through the foreign key before its update needed an exclusive one. Spring Security then denied the `/error` dispatch, so a server error reached the client as 403. G1 therefore did not catch them, although D1 and P1 did. With the fix, a broken multipart request returned 400 on the fixed JAR and 403 on the baseline JAR.

`ConcurrentDecisionIntegrationTest`, which races four threads per claim on H2, **failed against the unfixed code**: all four racers succeeded on the first claim, for decisions and for payments. It passes after the fix. During the fixed run, a local Prometheus 3.15.0 (Compose `monitoring` profile, cached image) scraped both instances every 5 s. Both targets were `up`. The summed `claimflow_claim_transitions_total` matched the client exactly: 21 approved, 19 rejected, 21 paid, 100 submitted and 100 extracted. `claimflow_approval_latency_seconds_count` totalled 40. All six rules reported health `ok`, and none was pending or firing.

Descriptive timings for the fixed run: decision p50 39 ms and p95 89 ms; payment p50 51 ms and p95 125 ms; 837 requests in total. Per-claim detail is in [`evidence/multiworker_baseline_2026-10-24.json`](evidence/multiworker_baseline_2026-10-24.json) and [`evidence/multiworker_fixed_2026-10-24.json`](evidence/multiworker_fixed_2026-10-24.json), copied unchanged from the script's ignored `target/` output so `mvn clean` cannot remove them. **Limits:** this is one run of one fixed workload on one laptop, against one MySQL container over loopback. It covers no submissions raced across instances, crashes, database restart, lock timeouts or failover. The fixed run used the database left by the baseline run, after Flyway applied V6.

## Receipt image seam and benchmark contract, 20 October 2026

`./mvnw -q verify` with JDK 21.0.12.1 passed **70 tests, 0 failures, 0 errors, 0 skipped**, up from 56, including the JaCoCo service coverage gate. The new tests are:
- **Image validation.** PNG/JPEG detection by content, SHA-256 of the exact bytes, and rejection of empty, oversized, non-image and mislabelled uploads. OCR text normalization keeps field values unchanged.
- **Image path through the existing rules.** OCR text is queued through the existing job and audited. A repeated idempotency key replays without a second OCR call, and reuse with different input is rejected. An OCR total that disagrees with the claimed amount goes to manager correction, not review. A second image or typed text with the same receipt is caught as a duplicate. Malformed images and OCR failures create no claim. Without a configured engine, the endpoint returns 503 and the text path still works.
- **Benchmark contract.** A synthetic manifest validates. The validator rejects split leakage by group and merchant, missing provenance or authorization, expired retention, unsafe image paths and bad labels. A development scoring run through the production parser and amount rule matches hand-computed counts. Missing predictions count as wrong, and cross-split or unknown rows are rejected. The command line validates, scores and warns when receipts are synthetic.

**No OCR metric is reported.** The ten-row manifest describes invented receipts. Its "images" are 8×8 pixel patterns generated at test time, and the scores exist only to check the harness arithmetic. No OCR engine was added or run, and no real receipt was used. Accuracy and correction rate need an authorized, redacted, human-labelled receipt set.

## Frontend dependency audit, 16 October 2026

An online `npm audit` of the previously pinned frontend tree identified four advisories: Vite (high), Vitest (critical), and transitive `@vitest/mocker` (moderate) and `tinypool` (critical). Updated Vite to 6.4.4 and Vitest to 5.0.5, which npm identifies as fixed releases. Vitest 5 initially picked up the separate Playwright `e2e/roles.spec.ts` file; `vite.config.ts` now limits unit-test discovery to `src/**/*.test.{ts,tsx}`. The final `npm audit --audit-level=moderate` reported **0 vulnerabilities**; `npm test` passed **2/2** unit tests and `npm run build` passed. A sandboxed browser attempt could not start a loopback web server; with approved loopback access, `npm run test:e2e -- --reporter=line` passed **2/2 Chrome flows in 24.0 seconds** against the packaged H2 API and Vite. Playwright terminated both web servers afterward. These are local checks and a point-in-time registry advisory result, not remote CI or production security evidence.

## Generated API contract, 16 October 2026

Added Springdoc 2.8.16 to the Spring Boot 3.5.6 API. A focused MockMvc integration check fetched `/v3/api-docs` from the running Spring context and verified the title, bearer JWT scheme, `Idempotency-Key` request parameter, async claim route, and public token operation. It also verified an anonymous claim-list request was rejected with the application's existing HTTP 403 behavior. The full Java 21 `./mvnw -q verify` gate passed **55 tests, 0 failures, 0 errors, 0 skipped**, including the new contract check and JaCoCo service coverage gate. This verifies the generated local contract, not a third-party client, Swagger UI browser interaction, or any remote deployment.

## Local extraction-job metrics, 16 October 2026

Added the Spring Boot-managed Prometheus registry and a database-backed, four-state extraction-job gauge. The first scrape test found that Spring's test context disables metrics export by default; it now explicitly enables the Prometheus export for that test. A subsequent focused MockMvc check fetched `/actuator/prometheus` and found `claimflow_extraction_jobs` with a `PENDING` state label. The full Java 21 `./mvnw -q verify` gate passed **56 tests, 0 failures, 0 errors, 0 skipped** and the JaCoCo service coverage check. This proves a local scrape endpoint and gauge registration, not Prometheus collection, dashboarding, queue latency or alerts.

## MySQL distinct-key race and process restart, 15 October 2026

The cached `mysql:8.4` image and existing named volume were used with a host-run Java 21 JAR on loopback. `python3 scripts/mysql_different_key_smoke.py` released two synchronous submissions with different idempotency keys and the same unique synthetic receipt at a barrier. Exactly one received HTTP 200; the other received the expected duplicate-receipt HTTP 400. The employee view exposed exactly one claim with that receipt hash, in `IN_REVIEW`. This is one two-request race attempt, not a high-contention or multi-instance measurement.

`python3 scripts/mysql_restart_smoke.py prepare` ran against the JAR with its extraction poller disabled. The claim remained `SUBMITTED` after two seconds. That process was terminated, and a new JAR process started with the poller enabled against the same MySQL volume. `verify` found the same claim in `IN_REVIEW` with `SUBMITTED → EXTRACTED` audit actions. A direct query of `extraction_jobs` confirmed the saved claim's job row was `DONE` with zero failed attempts. This proves one pending database job survived that API process restart and was picked up by the new worker. It does not test a database restart, crash during processing, concurrent worker instances, or backup restoration. The JAR and P06 MySQL container were stopped afterward; the named volume was retained.

## Real browser role flows, 13 October 2026

`npm run test:e2e -- --reporter=line` passed **2/2 Playwright tests in 23.9 seconds** using installed Google Chrome, one worker, a packaged Java 21 API with a 512 MiB heap cap and in-memory H2, and the Vite frontend. The first test used the browser UI to submit a receipt as an employee, waited for scheduled extraction, reviewed and approved as a manager, marked paid as finance, then confirmed `PAID` in the employee's history. The second submitted invalid receipt text and used the manager UI to supply a corrected transcription, returning the claim to review. Playwright shut down both web servers; a subsequent loopback port check found no listeners on 8080 or 5173. This is a local happy-path and correction check with fixture credentials, not a cross-browser, accessibility, load, MySQL, or production deployment test. The GitHub Actions browser job is authored but has not run remotely. At that time the dependency install reported four npm advisories (one moderate, one high, two critical). They were triaged and cleared by the 16 October frontend dependency update above.

## Concurrent duplicate receipt classification, 13 October 2026

The latest offline Maven verify passed **54 tests, 0 failures, 0 errors, 0 skipped**. A new H2 two-thread test submitted the same text receipt under different idempotency keys at once. Exactly one claim succeeded; the other produced the existing `Duplicate receipt and expense details` input error after the unique-index loser rolled back, and only one row had the receipt hash. This exercises H2 transaction/error translation. The equivalent different-key race has not been run against MySQL; the earlier MySQL smoke checked a sequential duplicate and a simultaneous same-key race.

## MySQL V3/V4 and concurrency smoke, 13 October 2026

Docker Desktop started with the cached `mysql:8.4` image; no image was pulled or built. The existing named MySQL volume was retained. A locally packaged Java 21 JAR connected through the `mysql` profile on loopback. Flyway validated all four migrations and applied V3 and V4 successfully, reaching version 4. A database query showed versions 1–4 all successful, the `uq_claims_receipt_hash` unique index present, and one completed async job persisted with status `DONE`.

`python3 scripts/mysql_smoke.py` passed the sequential HTTP flow: same-key replay returned one ID, a second key using the same receipt was rejected, manager review and approval succeeded, finance payment succeeded, and audit actions were `SUBMITTED → APPROVED → PAID`. `python3 scripts/mysql_concurrency_smoke.py` passed its two simultaneous same-key async submissions: both callers received HTTP 200 and the same ID, the scheduled worker reached `IN_REVIEW`, and another key using the same receipt was rejected. The server log contained the expected MySQL duplicate-idempotency-key error from the losing insert; the coordinator recovered it into a matching replay response. These are bounded single-process, two-request checks, not load, restart, multi-instance, or failure-injection measurements.

After verification, the JAR process was terminated, the P06 MySQL container was stopped, `docker ps` was empty, and Docker Desktop was stopped. No volume or image was pruned. The full API Docker image and Compose API service remain unverified.

## H2 scheduling and concurrency, 12 October 2026

The latest offline Maven verify passed **53 tests, 0 failures, 0 errors, 0 skipped**. One test released two H2-backed async submissions with the same idempotency key at once: both callers received the same claim ID, and only one job remained. The losing insert raised the expected unique-key violation internally; `ClaimSubmissionCoordinator` reloaded and compared the committed claim. A separate test confirmed Flyway V4's unique receipt-hash index rejects a second direct claim insert. Another Spring context enabled the real 50 ms scheduled poller and observed a committed async claim advance to `IN_REVIEW` with a `DONE` job within the test's eight-second deadline. This is a bounded local functional check, not a latency benchmark or restart durability test. `scripts/mysql_concurrency_smoke.py` passed Python AST syntax parsing only; it has not contacted a MySQL-backed service.

The latest JaCoCo report measured ClaimService **96/109 lines (88.1%)**, ClaimSubmissionCoordinator **5/6 (83.3%)**, ExtractionPoller **15/15 (100%)**, ExtractionProcessor **23/27 (85.2%)**, and PolicyRetriever **29/29 (100%)**. These numbers include the local H2 and authored development tests; they do not establish MySQL isolation behavior or multi-instance throughput. MySQL V3/V4 application and same-key race recovery remain **unverified** until the container/JAR smoke is run.

## Lexical policy retrieval, 12 October 2026

`JAVA_HOME=<Java 21 installation> ./mvnw -o -q -Dtest=PolicyQaEvalTest,DomainTest test` passed. The 30 authored questions yielded **27/27 relevant-clause hits at rank 1 (Recall@1 = 100%)**, **27/27 hits within the top 3 (Recall@3 = 100%)**, and **3/3 abstentions** on the three unanswerable questions. The same cases previously gave the substring baseline 15/27 rank-1 hits. The aliases were selected with this development set visible, so these numbers are in-sample and should not be used as an independent estimate of performance. The answer is the retrieved clause text, not a model-generated answer; no human-labelled answer correctness or vector retrieval has been measured.

## Local async text extraction, 12 October 2026

`JAVA_HOME=<Java 21 installation> ./mvnw -o -q verify` passed with **50 tests, 0 failures, 0 errors, 0 skipped**. H2/Flyway applied V1–V3 and validated the JPA schema. New HTTP/integration checks submitted a `SUBMITTED` claim and job in one transaction, replayed its idempotency key, rejected cross-path reuse, processed a valid text receipt once, observed `IN_REVIEW` and an extraction audit event, and verified the completed job cleared raw text. A malformed transcription entered `NEEDS_HUMAN_CHECK`; an employee could not correct it, while a manager could provide a valid transcription and move it to review. A separate test drove three simulated technical failures through the failure recorder and observed a dead job plus a human-check state. The unit test checked 2- and 4-second backoff and the three-attempt limit. A poller unit test confirmed that a processor exception is passed to the independent failure recorder. These checks are deterministic service/HTTP tests; no background timing, process restart, MySQL V3, RabbitMQ, image OCR, or LLM behavior was measured.

The JaCoCo report after the latest run measured ClaimService **93/105 lines (88.6%)**, ExtractionProcessor **23/27 (85.2%)**, ExtractionFailureService **14/14 (100%)**, ExtractionPoller **13/15 (86.7%)**, ExtractionJob **31/33 (93.9%)**, and ReceiptParser **23/26 (88.5%)**. The scheduled timing and restart behavior still need a runtime observation. The `*Service` class threshold passed, but overall branch and system reliability are not implied by that gate.

`npm test -- --run` passed **2/2 frontend tests**, including a manager correction interaction. `npm run build` passed TypeScript/Vite and produced a 152.45 kB JavaScript asset (49.10 kB gzip). These tests mock HTTP; there is no browser or end-to-end timing measurement.

The local async queue is an intermediate implementation. Extraction accuracy on a receipt benchmark, queue latency/throughput, RabbitMQ retry/DLQ behavior, and manual-review agreement remain **TBD**.

## MySQL integration, 12 October 2026

The MySQL 8.4 container reached healthy state on loopback port 13306. The packaged Java 21 JAR ran with the `mysql` profile against it. Flyway applied migrations V1 and V2 successfully, and Hibernate validated the schema. The reusable `scripts/mysql_smoke.py` request flow submitted a receipt, replayed the same idempotency key and received the original claim ID, rejected the same receipt under a new key, obtained an eligible manager review, approved and paid the claim, and read `SUBMITTED → APPROVED → PAID` audit actions in order. The smoke exited 0. This verifies the local MySQL/JPA/API path for one sequential flow; it does not cover concurrent submissions or the API Docker image.

The first smoke exposed an audit-order defect: MySQL stored `occurred_at` as whole seconds, causing three rapid events to sort arbitrarily. V2 changed it to `timestamp(6)`. A follow-up schema query confirmed that column type and both successful Flyway versions; the H2 integration suite also asserts event order. A database-generated sequence would be needed for a strict total order if events can share the same microsecond.

The multi-stage API image build was stopped while Maven downloaded its dependency graph because free host disk fell to about 6.7 GiB. The local MySQL integration used the already-built JAR; the full Compose API container remains unverified.

## 23:00 SGT resume run, 11 October 2026

`JAVA_HOME=<Java 21 installation> ./mvnw -o -q verify` completed with exit code 0 using Java 21 and Spring Boot 3.5.6. The Surefire reports show **45 tests run, 0 failures, 0 errors, 0 skipped**: two domain tests, two Spring Boot/MockMvc integration tests, 40 authored policy boundary cases, and one policy Q&A development-set evaluation. The integration tests booted H2, applied Flyway migration V1, issued JWTs, submitted and replayed a claim, rejected a duplicate receipt, enforced manager and finance roles, blocked approval on a hard policy violation, checked audit events, and rejected an invalid token. The first online dependency download also built successfully; Mockito's automatic agent attach failed on a subsequent sandboxed run, so the tests now use its subclass mock maker and pass without elevated execution.

The final JaCoCo report measured ClaimService **57/63 lines (90.5%)**, PolicyService **24/24 (100%)**, ReceiptParser **22/24 (91.7%)**, and JwtService **29/36 (80.6%)**. The Maven verify gate requires at least 80% line coverage for each `*Service` class. These are line coverage of the present implementation, not proof of all planned product behavior.

On 40 manually labelled development cases, the current deterministic hard rules matched the labels in all 40. These cases were written from the same fictional four-clause policy and are **not** a held-out estimate of human review quality. On 30 authored policy questions, the keyword answer baseline found the expected clause for **15/27 relevant questions (Recall@1 = 55.6%)** and abstained on **3/3 unanswerable questions**. It returns only one clause, so Recall@3 is not measured. The file `target/policy_qa_eval.json` contains raw counts. The misses are mainly paraphrases such as “cab” and “proof of purchase”; answer correctness has not been human-labelled.

No extraction accuracy, LLM review agreement, RAG recall, queue latency, production workload, MySQL Testcontainers run, browser e2e, Jenkins run, or cloud comparison has been measured. Those fields remain **TBD** under the P06 plan.

The frontend `npm run build` passed TypeScript and Vite with a 151.72 kB JavaScript bundle (48.91 kB gzip). `npm test` passed one mocked employee-flow test. This proves the present UI state path and compilation; no real-browser or full-stack e2e run has been done. `docker compose config --quiet` passed for the authored MySQL/API stack definition. The container stack itself has not yet run.

A packaged-jar HTTP smoke on `127.0.0.1:18080` returned status **200** for a receipt-text submission (`IN_REVIEW`), an eligible manager review, approval (`APPROVED`), a finance approved-list result of one, payment (`PAID`), and an audit history of three events. The service was shut down afterward. This used H2 in one process and is a single flow, not a load or reliability measurement.
