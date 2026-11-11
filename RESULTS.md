# ClaimFlow measured results

## Single-node MySQL crash and restart, first scored run, 11 November 2026

**Outcome: D1–D6 all passed in one local check.** The [frozen protocol](docs/DB_CRASH_PROTOCOL.md) was committed alone as `b10ad1d`, before the checker (`808f162`) and any database kill. The first scored run used that checker commit and the existing Java 21 JAR with SHA-256 `864ed0a1413e8a460654ebce0570df60a2be26fa143897c3e56506797e40380b`; no Java application code was changed. The saved [JSON evidence](evidence/db_crash/first_2026-11-11.json) records 11:15:56–11:16:13 SGT, the full 20 generated requests, SQL snapshots, health timings and every verdict. Its protocol SHA-256 is `49e4cdc1528488822484686bbf7740656b7d8ed8a9993fe60f27eed4831f2e38`. The public evidence replaces the author's home-directory prefix in command paths with `<project-root>`; measurements and verdicts are unchanged.

The checker started a fresh MySQL 8.4.0 data directory on loopback port 13317 with `innodb_flush_log_at_trx_commit=1` and `sync_binlog=1`. An API process on 18083 had polling disabled. It acknowledged **20 distinct synthetic async claims**, then SQL found the same 20 `SUBMITTED` claims, 20 `PENDING` jobs and 20 single `SUBMITTED` audit chains. The checker sent `SIGKILL` to its isolated MySQL PID; the API health probe became down in **0.01 s**. MySQL restarted from the same directory in **1.56 s**. The unchanged API reported healthy again within **0.02 s**, returned all 20 original IDs as `SUBMITTED`, and SQL still found the original 20 pending jobs and 20 single-event audit chains, with no unsuccessful Flyway row.

After the API restarted with polling enabled, it reported healthy in **4.13 s** and all 20 jobs became `DONE` in **1.64 s**. The final SQL snapshot had 20 `IN_REVIEW` claims and exactly one `SUBMITTED` plus one `EXTRACTED` audit per claim (40 events total). Replaying one identical submission/key returned its original ID and left the table at 20 claims. Ports 13317/18083 were free and the disposable data directory was deleted after the run. No invalidity condition or checker error was recorded.

**Limits.** This is a single-node MySQL process crash on one laptop, with 20 synthetic claims already acknowledged and no API request deliberately in flight at the kill. The author chose the settings after seeing earlier API-restart and API-kill results. `SIGKILL` is not a power failure or disk fault, and a single database cannot establish replica failover. The observed health and job timings are descriptive; this is not a throughput or production reliability estimate. The 7 November result below separately tested API kills while MySQL stayed running.

## Crash and sustained-load check, first scored run, 7 November 2026

**Outcome: pass on all nine pre-registered criteria in this one local run.** The protocol was committed alone as `f5d9aa3` on 6 November, before any load or kill. The checker was committed as `2c4cbac`; a socket-path fix and its regression test were committed as `927f627` before the scored run. The first scored run used the JAR built at 04:15 SGT from the unchanged Java sources; `927f627` changed only Python files. The protocol was unchanged. The run lasted from 05:57:54 to 06:08:09 SGT. Its full result is [evidence/crash_load/before_2026-11-06.json](evidence/crash_load/before_2026-11-06.json). The evidence filename retains the protocol's fixed `2026-11-06` date; the run itself occurred on **7 November**. The public JSON replaces the author's home-directory prefix in command paths with `<project-root>` and the workspace JDK path with `<workspace-jdk>`; measurements and verdicts are unchanged. No backend fix or `after` run was needed.

MySQL 8.4.0 from the local Anaconda installation ran in a fresh `target/crashdb/` directory on loopback port 13316, with default durability verified as `innodb_flush_log_at_trx_commit=1` and `sync_binlog=1`. Two Java 21 API processes used ports 18081 and 18082 and 384 MiB heap caps. Six submitters and 12 reviewers drove synthetic text claims for 600 seconds, plus a 120-claim burst. The checker killed A at 150.1 s, restarted A (healthy 4.7 s after restart), killed A and B at 360.3 s, and restarted both (healthy after 5.4 s and 0.1 s). MySQL was never killed.

| Pre-registered check | Observed result |
|---|---|
| C1 no lost acknowledged claim | 3,085 acknowledged; 3,085 database rows; 0 missing |
| C2 idempotency and duplicate receipt | 118 submit keys retried after a failed attempt; 0 keys with two claim rows or two returned IDs; 0 shared receipt hashes |
| C3 one decision | 3,085 accepted decisions and 3,085 competing 409s; 0 duplicate decision audit events or double 2xx replies |
| C4 one payment | 2,779 accepted payments and 2,779 competing 409s; 0 duplicate payment audit events or double 2xx replies |
| C5 audit matches state | 3,085 claims and 11,729 audit events checked; 0 mismatched chains or orphans |
| C6 jobs accounted for | 123 pending/retry jobs at the double kill; 0 remained pending/retry after drain; 2,128 `DONE`, 326 `DEAD` for unparseable receipts |
| C7 availability | 23,270 scored requests; 0 server 5xx, 0 disallowed 4xx, 0 transport failures outside a down window, 0 retry give-ups; 698 transport failures occurred against down instances |
| C8 recovery | All three restarts healthy within 60 s; 0 unsuccessful Flyway rows or manual repairs |
| C9 sustained | 1,001 state-changing 2xx in seconds 540–600 versus 972 in seconds 60–120 (ratio 1.03, threshold 0.50); both instances healthy; no `OutOfMemoryError` |

All four invalidity checks were clear: more than 1,000 claims were acknowledged, an in-flight transport failure was observed at each kill, the double-kill job snapshot was nonempty, and the disk watchdog never reached 8.1 GiB. The lowest sampled free space was **10.73 GiB**. The review queue drained in 2.3 s after submissions stopped. Teardown removed the disposable database directory and left no listeners on 13316, 18081 or 18082. The scripted 100-second shake-down before scoring acknowledged 514 claims and completed one kill/restart; its C9 display said `false` because the ten-minute comparison has no late window in a shake-down. That run was unscored and did not change the fixed criteria. Two earlier setup attempts generated no load: sandboxed MySQL initialization crashed, then the host-access attempt exposed a Unix socket path over macOS's limit.

**Limits.** The checker was authored by the same project team that chose the criteria, and its pass is one observation on one laptop with loopback traffic and a database that stayed running. `/actuator/health` probes are down detection and are reported separately from C7's client workload; a failed probe while a process starts is not a C7 failure. The synthetic receipt mix, selected kill times and at most about six new claims per second do not cover every interleaving, a database kill or restart, network partitions, power loss, real receipts or production capacity. No manual repair was needed in this run; that does not establish one would never be needed.

## Accessibility audit of the console, 6 November 2026

**What this is and is not.** This is an **automated** audit: axe-core 4.13.0 plus keyboard, focus and
accessibility-tree probes, driven by headless Google Chrome 156.0.8078.80 against the packaged API JAR
(default profile, in-memory H2) and the production console bundle served by `vite preview`. It is **not
a WCAG conformance result, and no WCAG conformance is claimed.** axe-core finds a subset of problems,
and a clean automated result does not make the console accessible. **A manual screen-reader pass by a
person (VoiceOver or NVDA) has not been done, and the console has not been tested with disabled users.**
Those remain open.

**Who chose the states, viewports and criteria.** The agent implementing this item chose all 35 states,
the three viewports, the axe tag set and the keyboard flows. The whole protocol is the script
`frontend/a11y/audit.mjs`, committed in `0b52595` **before any UI change**, and the `before` run used
that script unchanged. The `after` run used the same script, the same states and the same viewports.
No person reviewed the state list, so a state a real user depends on may be missing from it.

**Coverage.** Every one of the 35 states was scanned at 1280x900 and again at 390x844, giving
**70 axe runs per label** (90 to 91 axe rules evaluated per run); at 320x800 each state was checked for
horizontal overflow. Tags: `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`, `best-practice`.
The states cover all three roles and the signed-out page: empty and populated lists, the submit form,
each receipt-upload validation error, an upload in progress, the server's OCR 503, server 400 and 409
errors, policy review and policy block, decision and payment notices, stale decision and payment
conflicts, policy answers, and the API really stopped. Two states were **not reachable** on this API and
were not scanned: the `SUBMITTED` badge, because the worker moves a claim within about a second, and a
manager's note on an image claim, because no OCR engine is configured. All claims are synthetic.

| Measure | `before` (console at `4763a18`) | `after` (fix `54b033d`) |
|---|---|---|
| axe runs with at least one violation | **70 of 70** | **0 of 70** |
| Unique violations, as (rule, element selector) | **38** | **0** |
| Distinct (rule, tag and classes) patterns | **7** | **0** |
| Rules violated | `color-contrast` only | none |
| Impact | 38 serious | none |
| WCAG-tagged / best-practice-only | 38 / 0 | 0 / 0 |
| States with at least one violation | 35 of 35 | 0 of 35 |
| axe results needing a person ("incomplete") | 0 | 0 |
| States where focus sat on `<body>` | 27 of 35 | **1 of 35** (the sign-in page at load) |
| Upload errors linked to their control | 0 of 4 | **4 of 4** |
| Live messages announced in a pre-existing region | 0 of 19 | **27 of 27** |
| Distinct page titles across the states | 1 (`ClaimFlow`) | 4, one per view |
| States overflowing horizontally at 320px | 0 | 0 |
| Keyboard Tab cycle returned to its first stop | 4 of 4 views | 4 of 4 views |
| Tab stops with an empty accessible name / no focus style / positive tabindex | 0 / 0 / 0 | 0 / 0 / 0 |
| Tab stops repeating one role and name | 12 in the manager view (3 names x 4 claims) | **0** |
| Whole employee-manager-finance flow with no mouse | completed | completed |

All 38 `before` violations were contrast, in seven element patterns: `footer`, `p`, `p.hint`, `small`,
`span.eyebrow`, `span.muted` and `div.card.empty`. The fix darkened those text colours and the input
border, added a visible `:focus-visible` outline, moved focus to the new heading on sign-in, sign-out
and when a decided claim leaves the list, kept busy buttons focusable with `aria-disabled` instead of
`disabled`, linked each field error to its control with `aria-invalid`/`aria-describedby`, kept the live
regions permanently in the page, named each action button after its claim, set a per-view page title,
made the policy desk a top-level landmark, and turned API failures into sentences, with a stale-claim
409 refreshing the list.

Raw output for both runs is in [`evidence/a11y/audit_before_2026-11-06.json`](evidence/a11y/audit_before_2026-11-06.json)
and [`evidence/a11y/audit_after_2026-11-06.json`](evidence/a11y/audit_after_2026-11-06.json).

**Independent re-run.** The `after` audit was run a second time later the same day from the same
unchanged script, by a different agent, on a freshly packaged JAR, and saved as
[`evidence/a11y/audit_after-recheck_2026-11-06.json`](evidence/a11y/audit_after-recheck_2026-11-06.json)
(57 seconds). It reproduced every figure in the `after` column above: 35 of 35 states scanned, none
unreachable, **0 of 70 axe runs with a violation**, focus on `<body>` in the sign-in state only, 4 of 4
upload errors linked, 27 of 27 live messages in a pre-existing region, the same 4 page titles, no
horizontal overflow at 320px, all four Tab cycles returning to their first stop with no repeated
role-and-name stop, and the keyboard flow completed. The `before` and `after` tables above were also
recomputed from the raw `scans` arrays rather than read from each file's own `summary` block, and matched.

**Test suites after the fix.** `./mvnw -o -q verify` passed **77 tests, 0 failures, 0 errors, 0 skipped**,
unchanged from 24 October because the change is frontend-only. `npm test` passed **25 Vitest tests**
(10 before: 15 new tests in `Accessibility.test.tsx`, and one existing `App.test.tsx` assertion updated
for a longer button name). `npm run test:e2e` passed **5 of 5 Chrome flows** (3 role flows plus 2 new
accessibility flows: an axe scan of every role and error state, and a submission-to-payment journey
driven by the keyboard alone). Playwright reported 6.3 minutes for the five flows on the 6 November
re-run, against 1.2 minutes for three flows on 24 October; the laptop was running other work, so the
duration is not comparable and is not a measurement of anything.

**Limits.** One console bundle, one Chrome version, one browser engine, one laptop, three viewports and
35 states chosen by the agent that wrote the script. No screen reader, no voice control, no switch
access, no zoom or high-contrast mode, no `prefers-reduced-motion` check, no colour-blind simulation and
no user testing. The 320px check is horizontal overflow only, which is a rough stand-in for reflow, not
a reflow conformance test. Contrast was measured by axe on the rendered page, so a colour behind an
image or a gradient could be judged differently by a person. Nothing here was checked on a mobile
browser or on Windows.

## Crash and sustained-load check: pre-registered but not run on 6 November 2026 (historical)

**At this 6 November checkpoint there was no result.** The protocol was written and
committed in [docs/CRASH_LOAD_PROTOCOL.md](docs/CRASH_LOAD_PROTOCOL.md) (`f5d9aa3`), before any load or
kill, as required for a check whose outcome could otherwise be tuned.

**As of 6 November, no load had been generated and no process killed**, in a scored run or a shake-down
run. The first scored result is in the 7 November section above.

**The orchestrator did not exist at that time.** The protocol names `scripts/crash_load_check.py` as the client
and orchestrator, but `f5d9aa3` committed the protocol document alone. The script was added on 7 November
without changing any criterion or threshold in the committed protocol.

**Why it was blocked on 6 November: free disk.** The protocol's own rule declares a run **invalid** if the disk watchdog
stops it because free disk reached 8.1 GiB, which sits just above this workspace's 8 GiB floor. Free disk
on 6 November 2026 was **7.2 GiB**, already below both thresholds, so a run started then could not produce
a valid result under the rule that was fixed before the run. The disposable MySQL data directory the
protocol calls for, `target/crashdb/`, had been initialised at 194 MB; it was deleted on 6 November as
the protocol requires, taking free disk to 7.4 GiB, which is still below the floor. No `mysqld` was
running and nothing was listening on port 13316.

**What was estimated before the run.** These were **estimates**, written before any run:

- Roughly **2 to 3 GiB** of free space for one scored run, and enough headroom above the 8.1 GiB
  watchdog that ten minutes of growth cannot reach it, so about **11 GiB free** before starting. The
  estimate is the initialised data directory (194 MB measured) plus ten minutes of binary log and InnoDB
  growth at up to about 6 new claims per second with `sync_binlog=1`, plus the JSON evidence file and two
  API process logs. The binlog and redo growth is the uncertain part and is a guess.
- About **15 minutes of wall clock**: 600 seconds of load, up to 180 seconds of drain, plus set-up,
  the final SQL checks and teardown.
- The **workspace CPU lock**, held for the whole scored run, since the load generator, both API
  processes and the database all run on this one laptop.
- MySQL 8.4.0 from `/opt/anaconda3/bin/mysqld`, which is present, and free ports 13316, 18081 and 18082.

That blocked state was resolved on 7 November; see the scored result above.

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

`JAVA_HOME=../../.toolchains/jdk-21.0.12.1+1/Contents/Home ./mvnw -q verify` passed **70 tests, 0 failures, 0 errors, 0 skipped**, up from 56, including the JaCoCo service coverage gate. The new tests are:
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

`JAVA_HOME=../../.toolchains/jdk-21.0.12.1+1/Contents/Home ./mvnw -o -q -Dtest=PolicyQaEvalTest,DomainTest test` passed. The 30 authored questions yielded **27/27 relevant-clause hits at rank 1 (Recall@1 = 100%)**, **27/27 hits within the top 3 (Recall@3 = 100%)**, and **3/3 abstentions** on the three unanswerable questions. The same cases previously gave the substring baseline 15/27 rank-1 hits. The aliases were selected with this development set visible, so these numbers are in-sample and should not be used as an independent estimate of performance. The answer is the retrieved clause text, not a model-generated answer; no human-labelled answer correctness or vector retrieval has been measured.

## Local async text extraction, 12 October 2026

`JAVA_HOME=../../.toolchains/jdk-21.0.12.1+1/Contents/Home ./mvnw -o -q verify` passed with **50 tests, 0 failures, 0 errors, 0 skipped**. H2/Flyway applied V1–V3 and validated the JPA schema. New HTTP/integration checks submitted a `SUBMITTED` claim and job in one transaction, replayed its idempotency key, rejected cross-path reuse, processed a valid text receipt once, observed `IN_REVIEW` and an extraction audit event, and verified the completed job cleared raw text. A malformed transcription entered `NEEDS_HUMAN_CHECK`; an employee could not correct it, while a manager could provide a valid transcription and move it to review. A separate test drove three simulated technical failures through the failure recorder and observed a dead job plus a human-check state. The unit test checked 2- and 4-second backoff and the three-attempt limit. A poller unit test confirmed that a processor exception is passed to the independent failure recorder. These checks are deterministic service/HTTP tests; no background timing, process restart, MySQL V3, RabbitMQ, image OCR, or LLM behavior was measured.

The JaCoCo report after the latest run measured ClaimService **93/105 lines (88.6%)**, ExtractionProcessor **23/27 (85.2%)**, ExtractionFailureService **14/14 (100%)**, ExtractionPoller **13/15 (86.7%)**, ExtractionJob **31/33 (93.9%)**, and ReceiptParser **23/26 (88.5%)**. The scheduled timing and restart behavior still need a runtime observation. The `*Service` class threshold passed, but overall branch and system reliability are not implied by that gate.

`npm test -- --run` passed **2/2 frontend tests**, including a manager correction interaction. `npm run build` passed TypeScript/Vite and produced a 152.45 kB JavaScript asset (49.10 kB gzip). These tests mock HTTP; there is no browser or end-to-end timing measurement.

The local async queue is an intermediate implementation. Extraction accuracy on a receipt benchmark, queue latency/throughput, RabbitMQ retry/DLQ behavior, and manual-review agreement remain **TBD**.

## MySQL integration, 12 October 2026

The MySQL 8.4 container reached healthy state on loopback port 13306. The packaged Java 21 JAR ran with the `mysql` profile against it. Flyway applied migrations V1 and V2 successfully, and Hibernate validated the schema. The reusable `scripts/mysql_smoke.py` request flow submitted a receipt, replayed the same idempotency key and received the original claim ID, rejected the same receipt under a new key, obtained an eligible manager review, approved and paid the claim, and read `SUBMITTED → APPROVED → PAID` audit actions in order. The smoke exited 0. This verifies the local MySQL/JPA/API path for one sequential flow; it does not cover concurrent submissions or the API Docker image.

The first smoke exposed an audit-order defect: MySQL stored `occurred_at` as whole seconds, causing three rapid events to sort arbitrarily. V2 changed it to `timestamp(6)`. A follow-up schema query confirmed that column type and both successful Flyway versions; the H2 integration suite also asserts event order. A database-generated sequence would be needed for a strict total order if events can share the same microsecond.

The multi-stage API image build was stopped while Maven downloaded its dependency graph because free host disk fell to about 6.7 GiB. The local MySQL integration used the already-built JAR; the full Compose API container remains unverified.

## 23:00 SGT resume run, 11 October 2026

`JAVA_HOME=../../.toolchains/jdk-21.0.12.1+1/Contents/Home ./mvnw -o -q verify` completed with exit code 0 using Java 21 and Spring Boot 3.5.6. The Surefire reports show **45 tests run, 0 failures, 0 errors, 0 skipped**: two domain tests, two Spring Boot/MockMvc integration tests, 40 authored policy boundary cases, and one policy Q&A development-set evaluation. The integration tests booted H2, applied Flyway migration V1, issued JWTs, submitted and replayed a claim, rejected a duplicate receipt, enforced manager and finance roles, blocked approval on a hard policy violation, checked audit events, and rejected an invalid token. The first online dependency download also built successfully; Mockito's automatic agent attach failed on a subsequent sandboxed run, so the tests now use its subclass mock maker and pass without elevated execution.

The final JaCoCo report measured ClaimService **57/63 lines (90.5%)**, PolicyService **24/24 (100%)**, ReceiptParser **22/24 (91.7%)**, and JwtService **29/36 (80.6%)**. The Maven verify gate requires at least 80% line coverage for each `*Service` class. These are line coverage of the present implementation, not proof of all planned product behavior.

On 40 manually labelled development cases, the current deterministic hard rules matched the labels in all 40. These cases were written from the same fictional four-clause policy and are **not** a held-out estimate of human review quality. On 30 authored policy questions, the keyword answer baseline found the expected clause for **15/27 relevant questions (Recall@1 = 55.6%)** and abstained on **3/3 unanswerable questions**. It returns only one clause, so Recall@3 is not measured. The file `target/policy_qa_eval.json` contains raw counts. The misses are mainly paraphrases such as “cab” and “proof of purchase”; answer correctness has not been human-labelled.

No extraction accuracy, LLM review agreement, RAG recall, queue latency, production workload, MySQL Testcontainers run, browser e2e, Jenkins run, or cloud comparison has been measured. Those fields remain **TBD** under the P06 plan.

The frontend `npm run build` passed TypeScript and Vite with a 151.72 kB JavaScript bundle (48.91 kB gzip). `npm test` passed one mocked employee-flow test. This proves the present UI state path and compilation; no real-browser or full-stack e2e run has been done. `docker compose config --quiet` passed for the authored MySQL/API stack definition. The container stack itself has not yet run.

A packaged-jar HTTP smoke on `127.0.0.1:18080` returned status **200** for a receipt-text submission (`IN_REVIEW`), an eligible manager review, approval (`APPROVED`), a finance approved-list result of one, payment (`PAID`), and an audit history of three events. The service was shut down afterward. This used H2 in one process and is a single flow, not a load or reliability measurement.
