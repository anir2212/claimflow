# Two-instance locking check: pre-registered protocol

Written and committed on 24 October 2026, before any run. Results go in a new dated section of [RESULTS.md](../RESULTS.md), whatever they show. Nothing in this file is a result.

## Question

When two ClaimFlow API processes share one MySQL database, can concurrent manager decisions, finance payments or extraction workers apply the same state change twice?

## Why the answer may be "yes" today

Reading the code at commit `3998895`: `ClaimService.decide` and `ClaimService.markPaid` load the claim with a plain `findById`, check the state in memory, and write the new state. The `claims` table has no version column, and the claim row is not locked. Two transactions can both read `IN_REVIEW`, both pass the check, and both commit, each with its own audit event. Extraction jobs differ, because `ExtractionProcessor` takes a `SELECT … FOR UPDATE` lock on the job row first. The first run below tests that reading. It is not assumed.

## Set-up

- MySQL 8.4 from the already cached `mysql:8.4` image, started as a disposable Compose project: `docker compose -p p06check up -d mysql` (fresh named volume, port 127.0.0.1:13306). No image is pulled or built. Afterwards the project is removed with `docker compose -p p06check down -v`. The normal `p06_claimflow` volume is not touched.
- Two host-run API processes from the same JAR: `SPRING_PROFILES_ACTIVE=mysql`, the same JDBC URL, user and password, the same JWT secret, `-Xmx384m`, ports 18081 (instance A) and 18082 (instance B). Both extraction pollers are enabled.
- Client: `python3 scripts/multiworker_check.py --a http://127.0.0.1:18081 --b http://127.0.0.1:18082 --label LABEL`. It uses only the Python standard library. Every merchant name carries a random run nonce, so runs do not collide.
- Hardware: Apple M3 Pro, 11 cores, 18 GiB memory, macOS 15.3.1, shared with other jobs. Latency and throughput are recorded for description only. They are not pass criteria and are not a benchmark.

## Requests

1. **Set-up claims.** 40 synchronous meal claims with unique synthetic receipts (SGD 10.00–24.99, so no hard policy rule blocks approval), created alternately on A and B. Each must return 200 and `IN_REVIEW`; otherwise the run is invalid, not failed.
2. **Decision race.** For each claim, four requests: approve and reject on A, approve and reject on B. Claims run in 4 batches of 10. Each batch starts all 40 of its requests together from a thread barrier, giving 40 requests in flight, and 160 decision requests in total.
3. **Payment race.** For each claim that ended `APPROVED`, four `POST /paid` requests (two on A, two on B), again in batches of 10 claims released at one barrier.
4. **Two extraction workers.** 60 async text claims with valid synthetic receipts, submitted alternately on A and B, 10 at a time. Both pollers compete for the jobs. The client waits up to 90 seconds for every claim to leave `SUBMITTED`.

## Pass criteria (all must hold)

- **D1.** For every claim, exactly one of its four decision requests returns 2xx, and the other three return 409.
- **D2.** Every claim's final state matches the winning request: `APPROVED` for an approve and `REJECTED` for a reject.
- **D3.** Every claim's audit history has exactly one `APPROVED` or `REJECTED` event.
- **P1.** For every approved claim, exactly one payment request returns 2xx and the other three return 409. The final state is `PAID`, with exactly one `PAID` audit event.
- **E1.** All 60 async claims reach `IN_REVIEW` within 90 seconds. Each has exactly one `EXTRACTED` audit event, and none is in `NEEDS_HUMAN_CHECK`.
- **G1.** No request in any phase returns 5xx or fails at the transport level.

A run passes only if every criterion holds. The script exits 0 on a pass and 1 on any failure, and writes per-claim detail to `target/multiworker_LABEL.json`. That file is ignored by Git.

## Runs

1. **`baseline`**: the JAR built from commit `3998895`, before any locking change. It is reported as observed, pass or fail.
2. **`fixed`**: run only if a locking change is made, using the JAR built from that change. Same protocol, same thresholds, on the same database after Flyway applies any new migration. During this run, a local Prometheus server from the cached `prom/prometheus` image scrapes both instances with `ops/prometheus/prometheus-two-instances.yml`. The client's counts are then compared with the summed `claimflow_claim_transitions_total` counters. Rule health is recorded, along with any alert state. This is a local check, not a deployment.

If a criterion cannot be evaluated, for example because the database fails to start, the run is reported as incomplete rather than retried with changed criteria. Any later change to the protocol is added below with its date and reason, and the original text stays.

## Limits that apply whatever the outcome

Two processes on one laptop, against one MySQL container, over loopback. The test uses one fixed workload and no network partitions, process crashes or database failover. A pass shows that these specific races did not double-apply in this run. It does not prove that every interleaving is safe.
