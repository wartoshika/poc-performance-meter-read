# Results

Measured on 2026-10-06 between 20:20 and 23:50 CEST. Every number below comes from
`results/<scenario>.json`, written by `scripts/run-scenario.py`; the tables are the output of
`scripts/report.py`, except that "Pool pending max" is set to n/a where it is not instrumented
(HTTP/2, see Limitations). Nothing here is estimated. Two smoke tests in "Findings worth knowing"
and the `nghttp` reading in "Single-endpoint limits" were observed during development; their raw
output was not kept.

## Verdict

**Yes.** One instance with 2 vCPU and 2 GiB sustained 400 meter reads per second against one
single endpoint (one host:port) while 20 % of the reads took 1-10 s and 0.5 % never answered
within the 30 s timeout. It met all four "keeps up" criteria with HTTP/1.1 and with HTTP/2, and
with PostgreSQL writes enabled:

- completed 400.0 reads/s (target 400), 0 % late reads (no read started more than 1 s after its
  due time; the worst start lag in any 400/s run was 736 ms), 0 unexpected errors, and no heap
  growth above the criterion within the 10-minute window (this does not rule out a slow leak)
- at most 574 requests open at once (average 430, as Little's law predicts for this profile), far
  below the 4,000 worst case of the specification
- at 400/s the application used on average 36-48 % of one core (18-24 % of its 2 vCPU limit) and
  at most 79 MiB of heap
- the stress test met the four criteria at every step up to and including 4,000 reads/s, where
  the application used its full 2 vCPU; the criteria contain no latency bound, each step ran
  once, and at 4,000/s the host was saturated, so 4,000/s is the saturation point of this setup,
  not a proven capacity; rates above 4,000/s were not tested
- the JDK `java.net.http` client, originally planned as primary, kept up at 400/s as well; it did
  not negotiate HTTP/2 over cleartext (see the comparison)

Conditions under which this holds (all measured, see below):

1. One read = one virtual thread blocking on the Quarkus REST client; the outbound connection
   pool must be sized for the open requests, not left at the Quarkus default of 50 (with 50, 62 %
   of the reads timed out in a smoke test, see "Findings").
2. HTTP/1.1 needs one connection per open request: 435-453 on average and up to 529 connections to the single endpoint at
   400/s. HTTP/2 needed 4 connections at 400/s (server limit 128 concurrent streams per
   connection), 9-50 on average at 600-4,000/s.
3. The endpoint answers as profiled and is not itself the bottleneck (WireMock used at most
   44 % of one core at 400/s; 3.5 of its 4 CPUs at 4,000/s).
4. The timeout must leave headroom above the slowest legitimate answer: with a 10 s timeout and
   answers of up to 10 s, 2-6 good reads per 10 minutes timed out (0.001-0.003 %).

What it does not show: real WAN latency, TLS, a real access point's behaviour under load, a
long-running heap or connection trend beyond 10 minutes, write latency against a filled
production table, and the extra load of retries (see "Limitations").

## Versions

| Component | Version | Source of the number |
|---|---|---|
| JDK (application runtime) | Eclipse Temurin 25.0.4.1+1-LTS, image `eclipse-temurin:25.0.4.1_1-jre` (digest `sha256:fcd7fd7b...dceae636`) | `/poc/config` (`java.vm.version`) |
| JDK (build) | Eclipse Temurin 25.0.3+9-LTS | `java -version` on the host |
| Kotlin | 2.4.20 compiler and stdlib (latest 2.4.x on Maven Central; the Quarkus BOM pins stdlib 2.4.10, overridden in `build.gradle.kts`) | Maven Central metadata, `build/quarkus-app/lib` |
| Quarkus | 3.40.1 (latest stable on Maven Central on 2026-10-06; 4.0.0.Beta1 is the newest overall) | Maven Central metadata, startup log |
| Vert.x (under the Quarkus REST client) | 4.5.34 | `build/quarkus-app/lib` |
| Micrometer / HdrHistogram / PostgreSQL JDBC | 1.17.1 / 2.2.2 / 42.7.13 (from the Quarkus BOM) | Quarkus BOM |
| Gradle | 9.5.1 (wrapper) | `./gradlew --version` |
| WireMock | 3.13.2 standalone, image `wiremock/wiremock:3.13.2-alpine` (digest `sha256:f8c42a38...43c20e147`), runs on Temurin 17.0.19 | Maven Central (latest 3.x), Docker Hub |
| PostgreSQL | 18.6 (Debian 18.6-1.pgdg13+2), image `postgres:18.6` | `select version()` |
| Docker | Engine 29.5.3, Compose 5.1.4, cgroup v2 with systemd driver | `docker version`, `docker info` |
| Host OS | Ubuntu 26.04 LTS, kernel 7.0.0-34-generic | `/etc/os-release`, `uname -r` |
| Host CPU / RAM | VM guest (hypervisor reported as KVM) with 12 vCPUs on an Intel Core i7-8700K @ 3.70 GHz (6 physical cores, 12 threads); 55 GiB RAM | `lscpu`, `nproc`, `free -g` |

The host is a virtual machine (KVM, paravirtualized). No other significant workloads ran on it
during the measurements. The host load (1 min) during each run is in the tables; it ranged from
1.98 to 20.4 and comes from the test stack itself (application, WireMock, PostgreSQL, driver).

## Setup as measured

- Application container: `cpus: 2`, `mem_limit: 2g`, G1, `-XX:MaxRAMPercentage=75` (max heap
  1,536 MiB). JVM sees 2 processors.
- WireMock container: `cpus: 4`, `mem_limit: 2g`, `-Xmx1536m`, `--async-response-enabled=true`,
  `--async-response-threads=64`, `--container-threads=400`, `--no-request-journal`,
  `--disable-request-logging`, `--jetty-idle-timeout=120000`.
- PostgreSQL container (stage 2): `cpus: 2`, `mem_limit: 2g`, `shared_buffers=512MB`.
- All three on one Docker bridge network; the application calls only `http://wiremock:8080`.
- Each scenario: fresh stack, 60 s linear ramp-up, then the measurement window of 600 s
  (`Window s` in the tables). The application's window is reset at the end of the ramp-up, so
  every count, percentile and maximum covers only the 600 s window.
- Latency percentiles are exact (HdrHistogram, 3 significant digits) over all reads completed in
  the window. "ok reads" = HTTP 200 with a valid body; "all outcomes" includes 503 and timeouts.
- CPU % is per cent of one core from cgroup `cpu.stat`, averaged over 5 s samples; the
  application's limit is 200 %. Socket counts are `ss -tan` from inside the application
  container, filtered to the endpoint's address, sampled every 5 s (a spike shorter than 5 s can
  be missed).
- Injected outcomes: 1.0 % HTTP 503 and 0.5 % timeouts as configured; measured 1.49-1.50 % in every
  scenario with the full profile.

## Scenarios 1, 2, 3 and 5

| Metric | s1-baseline-http1 | s2-target-http1 | s3-target-http2 | s5-db-HTTP_2 |
|---|---|---|---|---|
| Client / protocol | quarkus HTTP_1_1 | quarkus HTTP_1_1 | quarkus HTTP_2 | quarkus HTTP_2 |
| Target /s | 400 | 400 | 400 | 400 |
| Achieved /s (completed) | 400.0 | 400.0 | 399.9 | 400.0 |
| Started /s | 400.0 | 400.0 | 400.0 | 400.0 |
| p50 / p95 / p99 / max ms (ok reads) | 129 / 197 / 206 / 667 | 148 / 5075 / 9028 / 10060 | 148 / 5083 / 8995 / 10068 | 149 / 5083 / 9011 / 10371 |
| max ms (all outcomes) | 667 | 30065 | 30065 | 30228 |
| Max open | 247 | 574 | 502 | 739 |
| Late % | 0.000 | 0.000 | 0.000 | 0.000 |
| Start lag p99 / p99.9 / max ms | 42 / 225 / 425 | 27 / 84 / 315 | 20 / 61 / 162 | 37 / 339 / 736 |
| Deferred | 0 | 0 | 0 | 0 |
| Injected % | 0.00 | 1.50 | 1.50 | 1.50 |
| Unexpected errors % | 0.000 (none) | 0.000 (none) | 0.000 (none) | 0.000 (none) |
| App CPU % of 1 core avg / max (limit 200) | 37 / 80 | 36 / 61 | 39 / 87 | 48 / 110 |
| WireMock CPU % avg / max (limit 400) | 27 / 34 | 28 / 35 | 31 / 42 | 33 / 44 |
| PostgreSQL CPU % avg / max (limit 200) | - | - | - | 18 / 25 |
| Heap used max MiB (of max) | 43 (of 1536) | 71 (of 1536) | 66 (of 1536) | 79 (of 1536) |
| Heap floor slope MiB/min | 0.03 | 0.00 | 0.11 | -0.05 |
| Connections ESTAB avg / max | 130 / 201 | 453 / 529 | 4 / 4 | 17 / 64 |
| TIME-WAIT max | 99 | 0 | 0 | 54 |
| Local ports in use max | 201 | 552 | 4 | 64 |
| Pool pending max | 0 | 0 | n/a | n/a |
| Responses by version | HTTP_1_1 240053 | HTTP_1_1 238861 | HTTP_2 238851 | HTTP_2 238847 |
| Platform threads max | 30 | 29 | 29 | 33 |
| GC pause max ms | 18 | 36 | 20 | 26 |
| App CPU throttled ms total / max per 5 s | 138 / 126 | 38 / 22 | 160 / 57 | 320 / 174 |
| Host load1 avg / max | 2.8 / 4.2 | 4.1 / 6.9 | 3.5 / 5.6 | 4.5 / 6.9 |
| Window s | 600 | 600 | 600 | 600 |
| Keeps up | yes | yes | yes | yes |

Scenario 2 versus 3: identical throughput, latency and error rates. HTTP/2 needed 4 connections
instead of 450-530 and slightly more application CPU (39 % vs 36 % of one core on average).
Because all traffic goes to one host:port, the connection count is the deciding factor, so
HTTP/2 was used for scenarios 4 and 5.

Scenario 5 (stage 2): 236,446 readings written in the window, 0 write errors, 0 duplicates (expected
without retries, as every (meter, due time) occurs once per 900 s and a run lasts 660 s); write
latency p50 1.06 ms, p95 3.63 ms, p99 6.86 ms, max 141 ms (pool of 32 connections, one
`INSERT ... ON CONFLICT (meter_id, due_at) DO NOTHING` per reading on the read's virtual thread).
PostgreSQL used 18 % of one core on average. After the run the table held 248,553 rows with
248,553 distinct (meter_id, due_at) keys, in the monthly partition `meter_reading_y2026m10`
(partitions for 2026-09 to 2026-12 exist). Idempotency was checked separately: re-inserting
1,000 existing rows with the application's statement inserted 0 rows
(`results/s5-idempotency-check.txt`).

## Scenario 4: stress

The test plan specifies 400, 600, 800 and 1,000/s. All four kept up, so the steps continued at 1,500,
2,000, 3,000 and 4,000/s. The 400/s step is scenario 3 (same protocol and profile). The rate
limit was set equal to the target rate in each step (`POC_RATE_LIMIT`).

**Highest sustainable rate measured: 4,000 reads/s** (HTTP/2, latency profile, 30 s timeout).
Every step kept up by all four criteria. **No rate above 4,000/s was tested** (the test series
ended there), so the point where the instance stops keeping up was not reached and is not
reported. At 4,000/s the instance was at its limit by every resource measure: application CPU
192 % on average and 201 % at most against the 200 % limit, 200 s of cgroup throttling in 600 s,
start lag up to 885 ms (threshold 1,000 ms), WireMock at up to 349 % of its 400 %, and host load
up to 20.4 on 12 vCPUs. Treat 4,000/s as the ceiling of this setup, not as headroom.


| Metric | s4-stress-600 | s4-stress-800 | s4-stress-1000 | s4-stress-1500 | s4-stress-2000 | s4-stress-3000 | s4-stress-4000 |
|---|---|---|---|---|---|---|---|
| Client / protocol | quarkus HTTP_2 | quarkus HTTP_2 | quarkus HTTP_2 | quarkus HTTP_2 | quarkus HTTP_2 | quarkus HTTP_2 | quarkus HTTP_2 |
| Target /s | 600 | 800 | 1000 | 1500 | 2000 | 3000 | 4000 |
| Achieved /s (completed) | 599.9 | 799.9 | 1000.0 | 1499.9 | 1999.8 | 2999.5 | 3999.5 |
| Started /s | 600.0 | 800.0 | 1000.0 | 1500.0 | 2000.0 | 3000.0 | 4000.0 |
| p50 / p95 / p99 / max ms (ok reads) | 149 / 5071 / 9028 / 10494 | 149 / 5091 / 9019 / 10322 | 149 / 5087 / 9028 / 10437 | 151 / 5087 / 9044 / 10609 | 150 / 5083 / 9019 / 10093 | 154 / 5091 / 9028 / 10543 | 166 / 5128 / 9060 / 11346 |
| max ms (all outcomes) | 30474 | 30376 | 30507 | 30736 | 30163 | 30376 | 31015 |
| Max open | 1056 | 1247 | 1744 | 3068 | 2503 | 4873 | 9430 |
| Late % | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |
| Start lag p99 / p99.9 / max ms | 87 / 340 / 664 | 80 / 268 / 472 | 76 / 333 / 614 | 93 / 479 / 769 | 28 / 81 / 168 | 46 / 212 / 399 | 59 / 506 / 885 |
| Deferred | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Injected % | 1.50 | 1.49 | 1.49 | 1.49 | 1.49 | 1.50 | 1.49 |
| Unexpected errors % | 0.000 (none) | 0.000 (none) | 0.000 (none) | 0.000 (none) | 0.000 (none) | 0.000 (none) | 0.000 (none) |
| App CPU % of 1 core avg / max (limit 200) | 51 / 82 | 64 / 85 | 77 / 118 | 101 / 149 | 125 / 153 | 167 / 199 | 192 / 201 |
| WireMock CPU % avg / max (limit 400) | 44 / 61 | 57 / 76 | 70 / 117 | 102 / 186 | 130 / 177 | 201 / 244 | 263 / 349 |
| PostgreSQL CPU % avg / max (limit 200) | - | - | - | - | - | - | - |
| Heap used max MiB (of max) | 98 (of 1536) | 126 (of 1536) | 150 (of 1536) | 300 (of 1536) | 328 (of 1536) | 416 (of 1536) | 622 (of 1536) |
| Heap floor slope MiB/min | 0.47 | -0.13 | 0.09 | 0.53 | 1.40 | 1.59 | -0.12 |
| Connections ESTAB avg / max | 9 / 20 | 10 / 21 | 22 / 64 | 30 / 64 | 20 / 30 | 42 / 64 | 50 / 64 |
| TIME-WAIT max | 9 | 7 | 42 | 41 | 11 | 31 | 20 |
| Local ports in use max | 20 | 21 | 69 | 75 | 31 | 95 | 72 |
| Pool pending max | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| Responses by version | HTTP_2 358237 | HTTP_2 477691 | HTTP_2 597179 | HTTP_2 895690 | HTTP_2 1194416 | HTTP_2 1791729 | HTTP_2 2388911 |
| Platform threads max | 33 | 33 | 36 | 48 | 68 | 62 | 73 |
| GC pause max ms | 20 | 56 | 57 | 54 | 126 | 128 | 144 |
| App CPU throttled ms total / max per 5 s | 45 / 44 | 334 / 94 | 1781 / 378 | 6629 / 1906 | 6381 / 1688 | 57478 / 8662 | 199634 / 9452 |
| Host load1 avg / max | 5.0 / 8.0 | 5.3 / 10.6 | 5.3 / 9.5 | 8.0 / 11.2 | 7.7 / 11.2 | 9.9 / 12.6 | 15.7 / 20.4 |
| Window s | 600 | 600 | 600 | 600 | 600 | 600 | 600 |
| Keeps up | yes | yes | yes | yes | yes | yes | yes |

## Follow-up: 10 s instead of 30 s timeout

Follow-up question: does a 10 s timeout per attempt make a difference? Scenarios 2 and 3 were
repeated with `--timeout 10`, everything else unchanged.

| Metric | s2-target-http1 | t2-target-http1-timeout10 | s3-target-http2 | t3-target-http2-timeout10 |
|---|---|---|---|---|
| Client / protocol | quarkus HTTP_1_1 | quarkus HTTP_1_1 | quarkus HTTP_2 | quarkus HTTP_2 |
| Target /s | 400 | 400 | 400 | 400 |
| Achieved /s (completed) | 400.0 | 400.0 | 399.9 | 400.0 |
| Started /s | 400.0 | 400.0 | 400.0 | 400.0 |
| p50 / p95 / p99 / max ms (ok reads) | 148 / 5075 / 9028 / 10060 | 149 / 5083 / 9028 / 10019 | 148 / 5083 / 8995 / 10068 | 148 / 5071 / 9036 / 10002 |
| max ms (all outcomes) | 30065 | 10256 | 30065 | 10043 |
| Max open | 574 | 572 | 502 | 449 |
| Late % | 0.000 | 0.000 | 0.000 | 0.000 |
| Start lag p99 / p99.9 / max ms | 27 / 84 / 315 | 67 / 241 / 435 | 20 / 61 / 162 | 16 / 37 / 83 |
| Deferred | 0 | 0 | 0 | 0 |
| Injected % | 1.50 | 1.50 | 1.50 | 1.50 |
| Unexpected errors % | 0.000 (none) | 0.003 (timeout 6) | 0.000 (none) | 0.001 (timeout 2) |
| App CPU % of 1 core avg / max (limit 200) | 36 / 61 | 36 / 64 | 39 / 87 | 40 / 80 |
| WireMock CPU % avg / max (limit 400) | 28 / 35 | 28 / 37 | 31 / 42 | 32 / 41 |
| PostgreSQL CPU % avg / max (limit 200) | - | - | - | - |
| Heap used max MiB (of max) | 71 (of 1536) | 66 (of 1536) | 66 (of 1536) | 64 (of 1536) |
| Heap floor slope MiB/min | 0.00 | -0.07 | 0.11 | 0.23 |
| Connections ESTAB avg / max | 453 / 529 | 435 / 518 | 4 / 4 | 4 / 4 |
| TIME-WAIT max | 0 | 2 | 0 | 0 |
| Local ports in use max | 552 | 589 | 4 | 4 |
| Pool pending max | 0 | 0 | n/a | n/a |
| Responses by version | HTTP_1_1 238861 | HTTP_1_1 238824 | HTTP_2 238851 | HTTP_2 238853 |
| Platform threads max | 29 | 32 | 29 | 28 |
| GC pause max ms | 36 | 18 | 20 | 22 |
| App CPU throttled ms total / max per 5 s | 38 / 22 | 40 / 38 | 160 / 57 | 170 / 128 |
| Host load1 avg / max | 4.1 / 6.9 | 3.3 / 7.5 | 3.5 / 5.6 | 3.5 / 5.2 |
| Window s | 600 | 600 | 600 | 600 |
| Keeps up | yes | no (noUnexpectedErrors) | yes | no (noUnexpectedErrors) |

- Open requests: average 430 -> 391 (HTTP/1.1) and 432 -> 392 (HTTP/2), sampled every 5 s. The
  difference of about 40 is exactly the hanging 0.5 %: 0.005 x 400/s x (30 s - 10 s) = 40. The
  maximum is dominated by short bursts and changed little (574 -> 572, 502 -> 449).
- Throughput, latency percentiles of good reads, CPU, heap and connection counts did not change
  measurably. At 400/s the 30 s timeout costs nothing the instance would notice.
- The cost of 10 s: 6 (HTTP/1.1) and 2 (HTTP/2) reads of the 5-10 s class timed out because
  their answer arrived just after 10 s (WireMock's upper bound is 10,000 ms plus processing).
  That is 0.001-0.003 %, but by the strict criterion these runs do not keep up. A timeout for
  answers of "up to 10 s" needs headroom above 10 s; anything between that and 30 s only shifts
  the open-request count by 0.005 x rate x timeout.

## Comparison: JDK `java.net.http.HttpClient`

The test plan originally named the JDK client as primary; during the work the plan changed to use the
Quarkus default HTTP client (the Quarkus REST client, on the Vert.x HTTP client), so all
scenarios above use it. The JDK client (virtual thread executor, one blocking `send()` per read)
was kept as the comparison and measured with scenarios 2 and 3:

| Metric | s2-target-http1 | c2-target-http1-jdk | s3-target-http2 | c3-target-http2-jdk |
|---|---|---|---|---|
| Client / protocol | quarkus HTTP_1_1 | jdk HTTP_1_1 | quarkus HTTP_2 | jdk HTTP_2 |
| Target /s | 400 | 400 | 400 | 400 |
| Achieved /s (completed) | 400.0 | 400.0 | 399.9 | 400.0 |
| Started /s | 400.0 | 400.0 | 400.0 | 400.0 |
| p50 / p95 / p99 / max ms (ok reads) | 148 / 5075 / 9028 / 10060 | 148 / 5079 / 9028 / 10068 | 148 / 5083 / 8995 / 10068 | 148 / 5079 / 8987 / 10093 |
| max ms (all outcomes) | 30065 | 30409 | 30065 | 30327 |
| Max open | 574 | 647 | 502 | 630 |
| Late % | 0.000 | 0.000 | 0.000 | 0.000 |
| Start lag p99 / p99.9 / max ms | 27 / 84 / 315 | 42 / 198 / 518 | 20 / 61 / 162 | 62 / 260 / 446 |
| Deferred | 0 | 0 | 0 | 0 |
| Injected % | 1.50 | 1.50 | 1.50 | 1.49 |
| Unexpected errors % | 0.000 (none) | 0.000 (none) | 0.000 (none) | 0.000 (none) |
| App CPU % of 1 core avg / max (limit 200) | 36 / 61 | 31 / 50 | 39 / 87 | 30 / 59 |
| WireMock CPU % avg / max (limit 400) | 28 / 35 | 30 / 40 | 31 / 42 | 29 / 43 |
| PostgreSQL CPU % avg / max (limit 200) | - | - | - | - |
| Heap used max MiB (of max) | 71 (of 1536) | 132 (of 1536) | 66 (of 1536) | 129 (of 1536) |
| Heap floor slope MiB/min | 0.00 | 1.10 | 0.11 | 0.42 |
| Connections ESTAB avg / max | 453 / 529 | 481 / 641 | 4 / 4 | 491 / 615 |
| TIME-WAIT max | 0 | 0 | 0 | 0 |
| Local ports in use max | 552 | 671 | 4 | 646 |
| Pool pending max | 0 | 0 | n/a | n/a |
| Responses by version | HTTP_1_1 238861 | HTTP_1_1 238854 | HTTP_2 238851 | HTTP_1_1 238845 |
| Platform threads max | 29 | 19 | 29 | 19 |
| GC pause max ms | 36 | 31 | 20 | 22 |
| App CPU throttled ms total / max per 5 s | 38 / 22 | 0 / 0 | 160 / 57 | 2 / 2 |
| Host load1 avg / max | 4.1 / 6.9 | 3.9 / 6.6 | 3.5 / 5.6 | 3.4 / 5.4 |
| Window s | 600 | 600 | 600 | 600 |
| Keeps up | yes | yes | yes | yes |

- The JDK client kept up too, with less CPU (30-31 % vs 36-39 % of one core) and more heap
  (129-132 MiB vs 66-71 MiB, heap floor slope 0.4-1.1 MiB/min, still under the criterion).
- **The JDK client did not use HTTP/2.** Configured with `HttpClient.Version.HTTP_2` against the
  cleartext endpoint, all 238,845 responses came back as HTTP/1.1 and it held 491 connections on
  average (615 max). Over `http://` the JDK client reaches HTTP/2 only through the h2c Upgrade
  mechanism and does not support prior knowledge; it did not upgrade these POST requests. The
  server does speak h2c (the Quarkus client and `nghttp` both negotiated it). With TLS the JDK
  client would negotiate h2 through ALPN; that was not tested.

## Single-endpoint limits

| Check | Measured |
|---|---|
| `ulimit -n` in the containers | soft 1,024 for a shell; hard 524,288. The JVM raises its own soft limit to the hard limit: `/proc/1/limits` shows 524,288/524,288 for the application and for WireMock. |
| Open file descriptors of the application JVM at the end of a run | 122 (s1), 475 (s2, HTTP/1.1), 38 (s3, HTTP/2), 65 (3,000/s, HTTP/2) |
| Ephemeral port range in the application container | 32768-60999 (28,232 ports per destination address and port) |
| Local ports in use towards the endpoint, max | 552-589 (Quarkus client, HTTP/1.1, 400/s), 4 (HTTP/2, 400/s), 95 (HTTP/2, 3,000/s); 671 for the JDK client |
| TIME-WAIT sockets towards the endpoint, max | 0-99 at 400/s; at most 42 under stress. `tcp_fin_timeout` 60 s. Connections are closed by the client's 60 s keep-alive timeout after bursts, and on HTTP/1.1 timeouts. TIME-WAIT stays low at about 2 closes/s because WireMock keeps its side of a timed-out connection open until its 45 s answer: the samples show on average about 30 FIN-WAIT-2 sockets (30 s timeout) and 69 (10 s timeout), i.e. 2/s x (45 s - timeout). A real peer that never answers can leave orphaned sockets for up to `tcp_fin_timeout`. |
| HTTP/1.1 pool | size 4,000 (= `POC_MAX_CONNECTIONS`, gate in front of a Vert.x pool of the same size); exchanges in use max 572-574 (Quarkus client), 647 (JDK client); ESTAB connections max 518-529 (Quarkus), 641 (JDK); pending acquires 0 in every run |
| HTTP/2 server limit | `SETTINGS_MAX_CONCURRENT_STREAMS` = 128, initial window 524,288, read with `nghttp -nv http://wiremock:8080/...` from the application container. WireMock 3.13.2 has no option to change it (Jetty's default). |
| HTTP/2 connections needed | 4 at 400/s (432 open on average = 108 streams per connection); 9-42 on average at 600-3,000/s. The client cap was 64 (`POC_HTTP2_MAX_CONNECTIONS`). |
| HTTP/2 cap reached | yes, briefly: max 64 in s5, at 1,000, 1,500, 3,000 and 4,000/s. Each time after a burst of starts (start-lag spike), when Vert.x opened connections in parallel for the queued streams; steady state stayed far below. Whether a stream waited for a connection at the cap is not instrumented (see Limitations). Up to 3,000/s the latency percentiles did not move; at 4,000/s p50 rose from 149 to 166 ms and the maximum of good reads to 11.3 s, and up to 9,430 reads were open against a stream capacity of 64 x 128 = 8,192, so streams may have queued in Vert.x or virtual threads waited for CPU; the data cannot tell which. |

## WireMock was not the bottleneck

- CPU: at most 44 % of one core at 400/s, 244 % at 3,000/s and 349 % at 4,000/s, against a limit
  of 400 %. At 4,000/s WireMock was close to its own limit. Memory at most 1,258 MiB of 2 GiB
  (fixed 1.5 GiB heap) up to 3,000/s.
- Latency matches the configured profile without queueing: good reads had p50 148 ms (fast
  class 50-200 ms), p95 5.07-5.09 s and p99 8.99-9.04 s at every rate from 400 to 3,000/s; at
  4,000/s p50 166 ms, p95 5.13 s, p99 9.06 s, max 11.3 s, the first sign of queueing. For the
  profile, p99 of the good reads falls at the 80th percentile of the 5-10 s class, 9.0 s. The
  hanging reads ended at 30.06-31.0 s (timeout 30 s).
- No scaling to several WireMock containers was needed.

## Findings worth knowing

- **Quarkus REST client pool default.** With its default `connection-pool-size` of 50, a 60 s
  smoke test at 400/s (HTTP/1.1) completed only 98 reads/s, held 20,094 open requests and timed
  out 62 % of the reads: requests queue for a connection and the queue time counts against the
  timeout. The pool size set in Vert.x `HttpClientOptions` is overridden by the REST client's own
  property; it has to be set as `io.quarkus.rest.client.connection-pool-size` (done in
  `QuarkusAccessPoint`).
- **`QuarkusRestClientBuilder.httpClientOptions(...)`**, mentioned in the Quarkus guide, does
  not exist in 3.40.1. The options are passed as a registered `ContextResolver<HttpClientOptions>`
  instead; that they take effect is visible in the HTTP/2 runs (prior knowledge, 100 % HTTP_2).
- **Rate limit equal to the target rate.** With a token bucket of only 50 ms burst, one short
  stall of the scheduler thread left a permanent backlog of a few reads, because refill equals
  demand: in a 60 s smoke test 79 % of the reads were counted as deferred (none late). The bucket now holds
  one second of tokens; deferred reads were 0 in every run.
- **Start lag** stayed below the 1 s threshold everywhere: worst 919 ms in the first baseline run
  (round 1, wall-clock based), 885 ms at 4,000/s in the final runs. Spikes coincide with CPU
  throttling of the application container (cgroup `throttled_usec` up to 1.9 s per 5 s at
  1,500/s, 9.5 s per 5 s at 4,000/s, summed over threads) and host load peaks, not with GC (max
  pause 144 ms).
- **Platform threads** stayed at 28-36 up to 1,000/s and grew to 48 (1,500/s), 62 (3,000/s) and
  73 (4,000/s); the reads themselves run on virtual threads.

## Round 1 (repeat runs)

Scenarios 1-3 were run once before the final round with an earlier build that measured due times
and start lag against the wall clock instead of the monotonic clock (the only difference). Kept
in `results/round1/` as a repeat measurement:

| Metric | s1-baseline-http1 | s2-target-http1 | s3-target-http2 |
|---|---|---|---|
| Client / protocol | quarkus HTTP_1_1 | quarkus HTTP_1_1 | quarkus HTTP_2 |
| Target /s | 400 | 400 | 400 |
| Achieved /s (completed) | 399.9 | 399.9 | 400.0 |
| Started /s | 400.0 | 400.0 | 400.0 |
| p50 / p95 / p99 / max ms (ok reads) | 129 / 198 / 211 / 1243 | 148 / 5071 / 9019 / 10019 | 148 / 5091 / 9060 / 10011 |
| max ms (all outcomes) | 1243 | 30065 | 30097 |
| Max open | 461 | 492 | 497 |
| Late % | 0.000 | 0.000 | 0.000 |
| Start lag p99 / p99.9 / max ms | 58 / 500 / 919 | 21 / 61 / 101 | 24 / 75 / 190 |
| Deferred | 0 | 0 | 0 |
| Injected % | 0.00 | 1.49 | 1.49 |
| Unexpected errors % | 0.000 (none) | 0.000 (none) | 0.000 (none) |
| App CPU % of 1 core avg / max (limit 200) | 38 / 79 | 36 / 72 | 38 / 68 |
| WireMock CPU % avg / max (limit 400) | 26 / 35 | 28 / 33 | 30 / 45 |
| PostgreSQL CPU % avg / max (limit 200) | - | - | - |
| Heap used max MiB (of max) | 37 (of 1536) | 72 (of 1536) | 68 (of 1536) |
| Heap floor slope MiB/min | 0.06 | 0.11 | 0.18 |
| Connections ESTAB avg / max | 165 / 407 | 448 / 471 | 4 / 4 |
| TIME-WAIT max | 325 | 0 | 0 |
| Local ports in use max | 407 | 499 | 4 |
| Pool pending max | 0 | 0 | n/a |
| Responses by version | HTTP_1_1 240077 | HTTP_1_1 238816 | HTTP_2 238845 |
| Platform threads max | 29 | 31 | 31 |
| GC pause max ms | 19 | 28 | 15 |
| App CPU throttled ms total / max per 5 s | n/a | n/a | n/a |
| Host load1 avg / max | 3.6 / 6.6 | 3.7 / 5.5 | 4.0 / 6.2 |
| Window s | 600 | 600 | 600 |
| Keeps up | yes | yes | yes |

## Deviations from the test plan, and what was not possible

- **Client:** the Quarkus REST client is the primary client, by a plan change during the work; the
  JDK client is the comparison (above).
- **HTTP/2 over cleartext:** the Quarkus client uses prior knowledge (`http2ClearTextUpgrade =
  false`); the JDK client cannot, see above.
- **WireMock cannot pick a mapping at random.** The latency classes are selected by the last three
  digits of the meter number. Each meter therefore always gets the same class; over time the
  classes are spread evenly because due order follows the golden-ratio phase. Fast share is
  78.5 %, because the 1 % 503 and 0.5 % hang are taken from it.
- **WireMock has no "never answer" fault.** The 0.5 % answer after 45 s, i.e. never within the
  30 s timeout. Its faults (`CONNECTION_RESET_BY_PEER`, `EMPTY_RESPONSE`, `MALFORMED_RESPONSE_CHUNK`,
  `RANDOM_DATA_THEN_CLOSE`) all answer quickly.
- **`SETTINGS_MAX_CONCURRENT_STREAMS` of WireMock is not configurable** (128, measured).
- **Ramp-up:** the phase model has no ramp, so the scheduler thins out due reads linearly
  from 0 % to 100 % over 60 s; skipped reads are counted, never issued, and fall outside the
  measurement window.
- **Timeout semantics:** the REST client's `readTimeout` is applied by Vert.x as the request
  timeout ("The timeout period of 30000ms has been exceeded while executing POST ..."); measured
  hanging reads ended at 30.06-31.0 s.
- **16 KB response limit:** both clients reject a response whose `Content-Length` exceeds 16 KB;
  the JDK client also aborts a body without length as soon as it passes 16 KB, the Quarkus client
  checks such a body after it has been buffered. Checked with `wiremock/profile-large`
  (20 KB bodies): in 35 s at 10 reads/s, 326 of 326 reads ended as `too_large` with the Quarkus client and 326 of 326 with
  the JDK client, 0 OK (`results/size-limit-check.txt`).
- **Heap criterion** "no upward trend" is made concrete as: least-squares slope of the per-minute
  heap floor below 5 MiB/min. The highest measured slope was 1.59 MiB/min (3,000/s); heap use
  peaked at 622 MiB of 1,536 MiB (4,000/s).

## Limitations

- Localhost networking over a Docker bridge: no WAN latency, no packet loss, no NAT or load
  balancer between client and endpoint. Round-trip time to the endpoint is well below 1 ms, so
  connection setup is nearly free here and costs a real RTT (more with TLS) in production.
- No TLS. HTTP/2 was cleartext (h2c). TLS adds CPU per connection and per byte, which matters
  most for HTTP/1.1 with hundreds of connections.
- The simulated access point is WireMock, not the real one: its HTTP/2 behaviour (stream limit,
  flow control, connection reuse) and its latency under load are assumptions.
- 10 minutes per scenario. Heap and connection trends over hours, and the behaviour at the
  15-minute interval boundary repeating many times, were not observed.
- The host is a VM whose 12 vCPUs are shared by the application, WireMock and the driver; host
  load reached 12.6 at 3,000/s and 20.4 at 4,000/s, so the stress results above 2,000/s are partly limited by the host, not only by the
  application's 2 vCPU.
- Stress steps above 4,000/s were not executed. Each step ran once; round 1 shows noticeable
  run-to-run spread in the tails (e.g. max start lag 919 vs 425 ms for s1).
- "Keeps up" has no latency criterion: with a 30 s timeout, request time could grow through
  client-side queueing without failing any criterion.
- The heap criterion uses the per-minute minimum of sampled heap use, not the heap after GC, and
  10 data points. It catches a fast leak only; e.g. 1.59 MiB/min (3,000/s) would exceed the heap
  within a day if it were real growth.
- The database stage wrote into an empty table. A monthly partition in production would hold
  hundreds of millions of rows, and inserts arrive in effectively random key order, so the
  measured write latency does not carry over. No stress step ran with database writes, and
  partitions are only created at startup.
- No retries are modelled: failed reads (503, timeout) are not repeated, and `Retry-After` is
  ignored. Retries add load on top of the measured rates.
- "Never answers" is simulated as an answer after 45 s, so WireMock closes its side of the
  connection; a real peer that stays silent behaves differently at the socket level.
- 5 s sampling for sockets, CPU and open requests; maxima of shorter spikes are taken from the
  application's own 1 s sampler (open requests, heap) where available.
- HTTP/2 stream waits inside the Vert.x pool are not instrumented; only the HTTP/1.1 gate counts
  pending acquires.
- One client instance. Whether several pods behind the same NodePort share the access point's
  connection or stream limits was not part of the test.

## Raw data

| Path | Content |
|---|---|
| `results/<scenario>.json` | configuration, summary, the application's full window report, limit and socket checks, every 5 s sample |
| `results/<scenario>.log` | driver output; `results/<scenario>.app.log` application log (no WARN or ERROR line in any run) |
| `results/round1/` | the first run of scenarios 1-3 |
| `results/s5-idempotency-check.txt`, `results/size-limit-check.txt` | the two functional checks |
| `results/run-*.out` | combined output of each batch |

Scenario names: `s1`-`s5` test plan, `t2`/`t3` 10 s timeout follow-up, `c2`/`c3` JDK client
comparison.
