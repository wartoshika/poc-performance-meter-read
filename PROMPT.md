# Prompt

This project has been created using the following prompt.

````markdown

Build a small proof of concept that indicates that a single service is able to sustain a load of 400 meter reads per
second against a single endpoint. A single endpoint can be seen as a host:port combination or a Kubernetes NodePort. A
share of the meter reads take up to 10 seconds to answer. Check whether slow answers raise the numbers if concurrently
open requests.

Create the project within this directory and use the following non-negotionable tech stack:

- Kotlin 2.4 on Java 25 LTS using virtual threads. One virtual thread per meter read
- Quarkus 3.40 as application framework
- PostgreSql for storing readings, relevant for stage 2
- Outbound calls over HTTP. Test both HTTP/1.1 with a connection pool and HTTP/2, because the access point is assumed to
  support HTTP/2. For the request use the java native HTTP Client using a virtual thread executor as primary client.
- WireMock in standalone mode as the simulated access point.
- Docker and/or Docker Compose to run the WireMock and PostgreSQL as separate containers.

Reverify correct version usage and report the exact versions in the report. If a technology does not fit the overall
purpose state it out in the report, do not work around the problem.

## Simulated Access Point (Covered by WireMock)

- Create exactly one endpoint/instance under `POST /meters/{meterId}/reading`, which is accessible under one host:port
  combination.
- The response body must be about 1 KB of JSON (meter number, reading, timestamp, status code).
- Utilize environment variables or mappings to provision latency profiles:
    - 80% answer in 50-200ms
    - 15% answer within 1-5s
    - 5% answer in 5-10s
    - optional: 1% return HTTP 503, optional: .5% never answer within the timeout
- Ensure that the configuration of WireMock is sufficient for the task ahead. It must not be the bottleneck. Use
  asynchronous delayed responses, plenty of container threads and enough memory. Monitor the process separately and
  document if WireMock saturates before the application. You can the scale its configuration up. Research good
  strategies like load balancing but report if such an approach would not be possible/suffice the task.

## The Application

- Create a scheduler that reads meters at a fixed target rate.
- Each meter has a fixed phase in [0,1) as fractional part of `n * 0.6180339887` for the n-th meter. When a meter is due
  to be read can be measured using `windowStart + (phase + k) * 900 s` where `k` is the iteration number and the
  interval is 15 minutes.
- A rate is derivable from the meter count. 360000 meters will give 400 reads/s, the meter count must be configurable.
- Each meter read must run on its own virtual thread with a timeout per attempt of 30 seconds. Maximum accepted response
  size is 16 KB.
- Metrics that should be exposed by the application, either using micrometer or OpenTelemetry:
    - amount of reads started per second, reads completed per second grouped by outcome
    - latency histogram where p50, p95, p99 and max must be visible
    - the current open requests
    - deferred or late meter reads (started later then its due time plus one second)
    - JVM meta metrics: heap, platform thread count and GC pauses
- for stage 2: every successful meter reading must be written into postgres. the table must be partitioned by month and
  a unique key must be placed on the meter number and due timestamp, so that repeated writes are idempotent.

## Test plan

Use multiple scenario with different test loadouts. Ensure that every scenario must run for at least 10 minutes
excluding a one minute ramp up phase. The applications container container must be limited to 2 vCPU and 2GiB memory so
that is ressembles a single pod. WireMock and Postgres must be run in separate containers using their own limits.

1. The baseline: 400/s, fast responses only without the slow share, HTTP/1.1
2. Target: 400/s including the latency profiles, HTTP/1.1
3. Target: 400/s including the latency profiles, HTTP/2
4. Stresstest: Increase the rate by 200 (400, 600, 800, 1000) including the latency profile and choose the better
   protocol from 2 and 3 until the service can no longer keep up. report the highest sustainable rate per second.

All of the criteria below must be true to treat the application as "can keep up":

- compleated reads per second must be within 2% of the target
- less then .1% of the meter start later then 1 seconds of their due time
- there must not be any error
- JVM heap must be stable, there must not be any upward trends.

## Possible Sideeffects

All traffic goes through one endpoint, a starvation of ports is possible. Therefore check:

- Ephemeral port usage and wait sockets for the endpoint
- overall connection pool size for HTTP/1.1
- The number of concurrent HTTP/2 connection and their streams

If there is an issue with ports on the system, report that because it is an important fact for the draft.

## Report and Deliverables

Next to typical sourcecode related files and all files related to the actual rest:

- README.md including: How to build, start and run each scenario to reproduce the results
- RESULTS.md including:
    - one table per scenario containing: Target rate, archived rate, histographic latency (ß50,p95,p99,max), max open
      requests, late meter reads %, error %, CPU %, heap max and connections used
    - the highest sustainable rate from scenario 4
    - a verdict to the initial question. yes, no, and under which conditions
    - additional versions to the Techstack above: Docker, OS and CPU/RAM
    - limitations of the poc, if something was left out

## Rules

- Do not assume or estimate, measure. If something does not run say so.
- Keep it small simple, it is a poc not a production application. Strip security, ui and don't use Kubernetes.

````

## Correction and direction changes while the agent worked

- Use the Quarkus default HTTP client instead of the JDK default one due to issues with HTTP/2. Keep the JDK one as the
  comparison in the report.
- Repeat scenarios 2 and 3 with a timeout of 10 s per attempt instead of 30 s.
- Let the agent evaluate further beyond the 1000 reads/s but stopped after 4000 reads/s because the throughput was sufficient enough.

## Changes after the measurements

Before publishing, personal information including paths have been neutralized. The code and measured numbers are
untouched.
