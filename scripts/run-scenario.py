#!/usr/bin/env python3
"""Run one scenario end to end and write results/<name>.json.

Starts WireMock (and PostgreSQL for stage 2) and the application with Docker Compose, waits
for the ramp-up, resets the application's measurement window, samples every 5 s for the
measurement duration, then collects the window report and the socket and limit checks.

Example:
    scripts/run-scenario.py --name s2-target-http1 --profile full --http-version HTTP_1_1
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = "http://127.0.0.1:18080"
INTERVAL_SECONDS = 900
SAMPLE_SECONDS = 5


def sh(*args, check=True, env=None):
    r = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, env=env)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def http_json(path, method="GET"):
    req = urllib.request.Request(APP + path, method=method)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.load(resp)


def wait_for(fn, what, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if fn():
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError(f"timed out waiting for {what}")


def container_id(service):
    return sh("docker", "compose", "ps", "-q", service).strip()


def cgroup_dir(cid):
    return Path(f"/sys/fs/cgroup/system.slice/docker-{cid}.scope")


def cpu_stat(cid):
    return {k: int(v) for k, v in (line.split() for line in (cgroup_dir(cid) / "cpu.stat").read_text().splitlines())}


def cpu_usec(cid):
    return cpu_stat(cid)["usage_usec"]


def mem_bytes(cid):
    return int((cgroup_dir(cid) / "memory.current").read_text())


def socket_states(target_ip):
    """TCP sockets of the app container towards the single endpoint, counted by state."""
    out = sh("docker", "exec", "meter-read-poc-app-1", "ss", "-Htan", "dst", f"{target_ip}:8080", check=False)
    states = {}
    ports = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        states[parts[0]] = states.get(parts[0], 0) + 1
        ports.add(parts[3].rsplit(":", 1)[-1])
    states["local_ports_in_use"] = len(ports)
    return states


def loadavg():
    return float(Path("/proc/loadavg").read_text().split()[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--profile", choices=["full", "fast"], default="full")
    ap.add_argument("--http-version", choices=["HTTP_1_1", "HTTP_2"], default="HTTP_1_1")
    ap.add_argument("--rate", type=int, default=400, help="target reads/s; meter count = rate * 900")
    ap.add_argument("--rate-limit", type=int, help="global limit, default max(400, rate)")
    ap.add_argument("--client", choices=["quarkus", "jdk"], default="quarkus")
    ap.add_argument("--max-connections", type=int, default=4000)
    ap.add_argument("--http2-max-connections", type=int, default=64)
    ap.add_argument("--db", action="store_true")
    ap.add_argument("--timeout", type=int, default=30, help="timeout per attempt in seconds")
    ap.add_argument("--ramp", type=int, default=60)
    ap.add_argument("--duration", type=int, default=600)
    ap.add_argument("--wiremock-cpus", default="4")
    args = ap.parse_args()

    env = dict(os.environ)
    env.update({
        "WIREMOCK_PROFILE": f"profile-{args.profile}",
        "WIREMOCK_CPUS": args.wiremock_cpus,
        "POC_METER_COUNT": str(args.rate * INTERVAL_SECONDS),
        "POC_RATE_LIMIT": str(args.rate_limit or max(400, args.rate)),
        "POC_RAMP_UP_SECONDS": str(args.ramp),
        "POC_HTTP_VERSION": args.http_version,
        "POC_MAX_CONNECTIONS": str(args.max_connections),
        "POC_CLIENT": args.client,
        "POC_HTTP2_MAX_CONNECTIONS": str(args.http2_max_connections),
        "POC_DB_ENABLED": "true" if args.db else "false",
        "POC_TIMEOUT_SECONDS": str(args.timeout),
    })
    profiles = ["--profile", "db"] if args.db else []

    print(f"[{args.name}] building the app image and resetting the stack", flush=True)
    sh("docker", "compose", "build", "-q", "app", env=env)
    sh("docker", "compose", "--profile", "db", "down", "-v", "--remove-orphans", env=env)
    services = ["wiremock"] + (["postgres"] if args.db else [])
    sh("docker", "compose", *profiles, "up", "-d", "--wait", *services, env=env)
    wait_for(lambda: urllib.request.urlopen("http://127.0.0.1:18081/__admin/health", timeout=2).status == 200,
             "wiremock")
    sh("docker", "compose", *profiles, "up", "-d", "--no-deps", "app", env=env)
    wait_for(lambda: http_json("/poc/config"), "app")
    config = http_json("/poc/config")
    started_at = time.time()
    print(f"[{args.name}] app up: {config['targetRate']:.0f}/s {config['client']} {config['httpVersion']}, ramp-up {args.ramp} s",
          flush=True)

    ids = {s: container_id(s) for s in services + ["app"]}
    target_ip = sh("docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                   ids["wiremock"]).strip()

    time.sleep(max(0, started_at + args.ramp - time.time()))
    http_json("/poc/window/reset", method="POST")
    print(f"[{args.name}] ramp-up done, measuring for {args.duration} s", flush=True)

    samples = []
    t_start = time.time()
    prev = {s: cpu_usec(c) for s, c in ids.items()}
    prev_thr = {s: cpu_stat(c)["throttled_usec"] for s, c in ids.items()}
    prev_t = time.time()
    next_t = t_start + SAMPLE_SECONDS
    while True:
        time.sleep(max(0, next_t - time.time()))
        now = time.time()
        cur = {s: cpu_usec(c) for s, c in ids.items()}
        cpu = {s: (cur[s] - prev[s]) / ((now - prev_t) * 1e6) * 100 for s in ids}
        prev, prev_t = cur, now
        thr = {s: cpu_stat(c)["throttled_usec"] for s, c in ids.items()}
        throttled = {s: round((thr[s] - prev_thr[s]) / 1000, 1) for s in ids}
        prev_thr = thr
        w = http_json("/poc/window")
        samples.append({
            "t": round(now - t_start, 1),
            "cpuPercent": {s: round(v, 1) for s, v in cpu.items()},
            "throttledMillis": throttled,
            "memBytes": {s: mem_bytes(c) for s, c in ids.items()},
            "sockets": socket_states(target_ip),
            "openNow": w["openNow"],
            "started": w["started"],
            "completed": w["completed"],
            "late": w["late"],
            "unexpectedErrors": w["unexpectedErrors"],
            "hostLoad1": loadavg(),
        })
        s = samples[-1]
        if len(samples) % 12 == 0:
            print(f"[{args.name}] t={s['t']:.0f}s completed/s={w['completedPerSecond']:.1f} open={s['openNow']} "
                  f"late={s['late']} unexpected={s['unexpectedErrors']} cpu app={s['cpuPercent']['app']}% "
                  f"wm={s['cpuPercent']['wiremock']}% estab={s['sockets'].get('ESTAB', 0)} "
                  f"tw={s['sockets'].get('TIME-WAIT', 0)} load={s['hostLoad1']}", flush=True)
        if now - t_start >= args.duration:
            break
        next_t += SAMPLE_SECONDS

    window = http_json("/poc/window")
    checks = {
        "ssSummary": sh("docker", "exec", "meter-read-poc-app-1", "ss", "-s"),
        "appUlimitSoft": sh("docker", "exec", "meter-read-poc-app-1", "sh", "-c", "ulimit -n").strip(),
        "appJvmOpenFilesLimit": re.sub(r"\s+", " ", sh("docker", "exec", "meter-read-poc-app-1", "sh", "-c",
                                                         "grep 'open files' /proc/1/limits")).strip(),
        "appOpenFds": int(sh("docker", "exec", "meter-read-poc-app-1", "sh", "-c", "ls /proc/1/fd | wc -l").strip()),
        "wiremockJvmOpenFilesLimit": re.sub(r"\s+", " ", sh("docker", "exec", "meter-read-poc-wiremock-1", "sh", "-c",
                                                              "grep 'open files' /proc/1/limits")).strip(),
        "ephemeralPortRange": sh("docker", "exec", "meter-read-poc-app-1", "cat",
                                 "/proc/sys/net/ipv4/ip_local_port_range").split(),
        "tcpFinTimeout": sh("docker", "exec", "meter-read-poc-app-1", "cat", "/proc/sys/net/ipv4/tcp_fin_timeout").strip(),
    }
    if args.db:
        q = ("select count(*), count(distinct (meter_id, due_at)), "
             "(select count(*) from pg_inherits where inhparent = 'meter_reading'::regclass) from meter_reading")
        checks["dbRows"] = sh("docker", "exec", "meter-read-poc-postgres-1", "psql", "-U", "poc", "-d", "poc", "-tAc", q).strip()
        checks["dbPartitionRows"] = sh("docker", "exec", "meter-read-poc-postgres-1", "psql", "-U", "poc", "-d", "poc", "-tAc",
                                       "select tableoid::regclass, count(*) from meter_reading group by 1").strip()

    target = config["targetRate"]
    def stat(key, svc=None, sub=None):
        vals = [(s[key][svc] if svc else s[key]) if sub is None else s[key].get(sub, 0) for s in samples]
        return {"avg": round(sum(vals) / len(vals), 1), "max": max(vals)}

    late_pct = 100.0 * window["late"] / max(1, window["started"])
    unexpected_pct = 100.0 * window["unexpectedErrors"] / max(1, window["completed"])
    slope = window["heapFloorSlopeMiBPerMinute"]
    verdict = {
        "completedWithin2Percent": abs(window["completedPerSecond"] - target) <= 0.02 * target,
        "lateBelow0.1Percent": late_pct < 0.1,
        "noUnexpectedErrors": window["unexpectedErrors"] == 0,
        "heapStable": slope is not None and slope < 5.0,
    }
    verdict["keepsUp"] = all(verdict.values())
    summary = {
        "targetRate": target,
        "completedPerSecond": round(window["completedPerSecond"], 2),
        "startedPerSecond": round(window["startedPerSecond"], 2),
        "latencyOkMillis": window["latencyOkMillis"],
        "latencyAllMillis": window["latencyAllMillis"],
        "openMax": window["openMax"],
        "latePercent": round(late_pct, 4),
        "deferred": window["deferred"],
        "unexpectedErrorPercent": round(unexpected_pct, 4),
        "unexpectedByOutcome": window["unexpectedByOutcome"],
        "responsesByHttpVersion": window["responsesByHttpVersion"],
        "poolPendingMax": window["poolPendingMax"],
        "injectedPercent": round(100.0 * sum(window["injectedByOutcome"].values()) / max(1, window["completed"]), 3),
        "cpuPercentApp": stat("cpuPercent", "app"),
        "cpuPercentWiremock": stat("cpuPercent", "wiremock"),
        "cpuPercentPostgres": stat("cpuPercent", "postgres") if args.db else None,
        "heapUsedMaxMiB": round(window["heapUsedMaxBytes"] / 1048576, 1),
        "heapFloorSlopeMiBPerMinute": slope,
        "establishedConnections": stat("sockets", sub="ESTAB"),
        "timeWait": stat("sockets", sub="TIME-WAIT"),
        "localPortsInUse": stat("sockets", sub="local_ports_in_use"),
        "startLagMillis": window["startLagMillis"],
        "appThrottledMillisTotal": round(sum(x["throttledMillis"]["app"] for x in samples), 1),
        "appThrottledMillisMaxPer5s": max(x["throttledMillis"]["app"] for x in samples),
        "platformThreadsMax": window["platformThreadsMax"],
        "gcPauseMaxMillis": window["gcPauseMaxMillis"],
        "hostLoad1": stat("hostLoad1"),
        "verdict": verdict,
    }
    out = {"name": args.name, "args": vars(args), "config": config, "summary": summary, "window": window,
           "checks": checks, "samples": samples,
           "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(started_at))}
    (ROOT / "results").mkdir(exist_ok=True)
    path = ROOT / "results" / f"{args.name}.json"
    path.write_text(json.dumps(out, indent=2))
    (ROOT / "results" / f"{args.name}.app.log").write_text(sh("docker", "compose", "logs", "--no-color", "app", env=env))
    print(json.dumps(summary, indent=2))
    print(f"[{args.name}] written {path.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
