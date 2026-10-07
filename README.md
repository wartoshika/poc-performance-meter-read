# Meter reading throughput PoC

> This repository has been created using AI and serves as a purpose to measure assumptions of a design concept.

Can one service instance sustain 400 meter reads per second against one single access point
endpoint (one host:port) while part of the reads takes up to 10 s to answer? This project
measures it. The results are in [RESULTS.md](RESULTS.md).

## Layout

| Path | Content |
|---|---|
| `src/main/kotlin/poc/MeterSchedule.kt` | Phase model: meter n has phase frac(n * 0.6180339887), due at windowStart + (phase + k) * 900 s |
| `src/main/kotlin/poc/ReadScheduler.kt` | Platform thread that walks the schedule, token bucket limit, one virtual thread per read |
| `src/main/kotlin/poc/MeterReader.kt` | One read: HTTP/1.1 connection gate, exchange, classification, JSON parse, optional DB write |
| `src/main/kotlin/poc/QuarkusAccessPoint.kt` | Default client: Quarkus REST client on the Vert.x HTTP client |
| `src/main/kotlin/poc/JdkAccessPoint.kt` | Comparison client: `java.net.http.HttpClient` with a virtual thread executor |
| `src/main/kotlin/poc/ReadingStore.kt` | Stage 2: PostgreSQL table partitioned by month, idempotent insert |
| `src/main/kotlin/poc/ReadStats.kt` | Micrometer meters and the exact measurement window (HdrHistogram) |
| `wiremock/profile-full`, `wiremock/profile-fast` | WireMock mappings: latency profile, or fast answers only |
| `wiremock/files/reading.json` | Templated ~1 KB response body |
| `docker-compose.yml` | WireMock, PostgreSQL (profile `db`) and the application, each with its own limits |
| `scripts/run-scenario.py` | Runs one scenario end to end and writes `results/<name>.json` |
| `scripts/run-all.sh` | The test plan |
| `scripts/report.py` | Prints the result tables from `results/*.json` |

## Requirements

- Docker with Compose v2 and cgroup v2 (the driver reads container CPU from
  `/sys/fs/cgroup/system.slice/docker-<id>.scope`, the systemd cgroup driver layout)
- JDK 25 to build (the Gradle wrapper downloads Gradle 9.5.1)
- Python 3 for the scripts

## Build

    ./gradlew build              # compiles, runs the unit tests, builds build/quarkus-app
    docker compose build app     # image meter-read-poc-app:latest from build/quarkus-app

`scripts/run-scenario.py` runs `docker compose build app` itself, but not Gradle: rebuild with
Gradle after every source change.

## Run the stack by hand

    docker compose up -d wiremock app                  # 400 reads/s, HTTP/1.1, latency profile
    curl -s localhost:18080/poc/config                 # effective configuration and versions
    curl -s localhost:18080/poc/window                 # measurement window since start or last reset
    curl -s -X POST localhost:18080/poc/window/reset
    curl -s localhost:18080/q/metrics | grep '^poc_'   # Micrometer, Prometheus format
    docker compose --profile db down -v

Ports on the host: application `127.0.0.1:18080`, WireMock `127.0.0.1:18081`, PostgreSQL
`127.0.0.1:15432`. Inside the compose network the application calls only
`http://wiremock:8080`.

## Configuration

Every application setting is an environment variable that `docker-compose.yml` passes through:

| Variable | Default | Meaning |
|---|---|---|
| `POC_METER_COUNT` | 360000 | Meter count; rate = count / 900 s |
| `POC_RATE_LIMIT` | 400 | Global limit of read starts per second (token bucket, capacity one second) |
| `POC_RAMP_UP_SECONDS` | 60 | Linear ramp from 0 to the full rate |
| `POC_CLIENT` | quarkus | `quarkus` (REST client, Vert.x) or `jdk` (java.net.http) |
| `POC_HTTP_VERSION` | HTTP_1_1 | `HTTP_1_1` or `HTTP_2` (cleartext, prior knowledge for the Quarkus client) |
| `POC_MAX_CONNECTIONS` | 4000 | HTTP/1.1 concurrent exchanges = connections |
| `POC_HTTP2_MAX_CONNECTIONS` | 64 | Upper bound of HTTP/2 connections (Quarkus client) |
| `POC_DB_ENABLED` | false | Stage 2: write every successful reading to PostgreSQL |
| `POC_TIMEOUT_SECONDS` | 30 | Timeout per attempt |
| `WIREMOCK_PROFILE` | profile-full | `profile-full` (latency profile) or `profile-fast` |
| `WIREMOCK_CPUS` | 4 | CPU limit of the WireMock container |

Maximum response size (16 KB) and the late threshold (1 s) are in
`src/main/resources/application.properties` (`poc.max-response-bytes`, `poc.late-threshold-millis`).

`wiremock/profile-large` answers every read with a 20 KB body; it exists only to check the 16 KB
limit (see RESULTS.md):

    WIREMOCK_PROFILE=profile-large POC_METER_COUNT=9000 docker compose up -d wiremock app
    sleep 30; curl -s localhost:18080/poc/window | python3 -m json.tool | grep -A9 completedByOutcome

### Latency profile

WireMock cannot pick a mapping at random, so the profile is selected by the last three digits of
the meter number (meter ids are `MTR` + 9 digits). Because due order follows the golden-ratio
phase, every class is spread evenly over time.

| Suffix | Share | Answer |
|---|---|---|
| 000-004 | 0.5 % | 200 after 45 s, i.e. never within the 30 s timeout |
| 005-014 | 1 % | 503 after 50-200 ms |
| 015-799 | 78.5 % | 200 after 50-200 ms |
| 800-949 | 15 % | 200 after 1-5 s |
| 950-999 | 5 % | 200 after 5-10 s |

The shares and delays are edited in `wiremock/profile-full/mappings/meter-reading.json`. The
application labels a timeout or a 503 as "injected" only when the meter id matches the suffix
classes above (`poc.analysis.*` in `application.properties`); every other non-OK outcome counts
as an unexpected error.

## Run the scenarios

Each scenario takes 60 s ramp-up plus 600 s measurement:

    scripts/run-all.sh base                       # 1 baseline, 2 HTTP/1.1, 3 HTTP/2
    BEST=HTTP_2 scripts/run-all.sh stress         # 4 at 600, 800, 1000 reads/s
    BEST=HTTP_2 RATES="1200 1400" scripts/run-all.sh stress
    BEST=HTTP_2 scripts/run-all.sh db             # 5 with PostgreSQL writes
    BEST=HTTP_2 scripts/run-all.sh all            # all of the above in one go
    scripts/run-all.sh timeout10                  # follow-up: scenarios 2 and 3 with a 10 s timeout
    scripts/run-all.sh jdk                        # comparison: scenarios 2 and 3 with the JDK client

Or a single run with any parameters:

    scripts/run-scenario.py --name my-run --profile full --http-version HTTP_2 --rate 800 \
        [--client jdk] [--db] [--timeout 10] [--ramp 60] [--duration 600]

Each run tears the stack down (`docker compose --profile db down -v`), starts it fresh, resets the
application's measurement window at the end of the ramp-up, samples every 5 s (container CPU from
cgroup `cpu.stat`, memory, `ss` socket states towards the endpoint from inside the application
container, open requests, host load) and writes:

- `results/<name>.json`: configuration, summary, the application's window report, limit and
  socket checks (`ss -s`, `ulimit -n`, `/proc/1/limits`, ephemeral port range), all samples
- `results/<name>.log`: driver output, `results/<name>.app.log`: application log

Then:

    scripts/report.py                   # all scenarios as one table
    scripts/report.py s2-target-http1 s3-target-http2

### "Keeps up" as the driver evaluates it

- completed reads per second within 2 % of the target
- less than 0.1 % of reads started more than 1 s after their due time
- no outcome other than the injected ones (OK, injected 503, injected timeout)
- heap stable: the least-squares slope of the per-minute heap floor (lowest sampled heap use in
  each minute) is below 5 MiB per minute

## Notes

- The application container is limited to 2 vCPU and 2 GiB (`cpus: 2`, `mem_limit: 2g`); the JVM
  takes 75 % of that as maximum heap (`-XX:MaxRAMPercentage=75`).
- The scheduler thread is a platform thread; every read runs on its own virtual thread and blocks
  it for the whole exchange.
- `docker compose --profile db down -v` removes everything this project starts, including the
  PostgreSQL data.