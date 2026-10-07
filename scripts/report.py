#!/usr/bin/env python3
"""Print the RESULTS.md tables from results/*.json (measured values only)."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def load(names):
    out = []
    for n in names:
        p = ROOT / "results" / f"{n}.json"
        if p.exists():
            out.append(json.loads(p.read_text()))
    return out


def row(d):
    s, w, c = d["summary"], d["window"], d["config"]
    lat = s["latencyOkMillis"]
    conns = s["establishedConnections"]
    versions = ", ".join(f"{k} {v}" for k, v in s["responsesByHttpVersion"].items())
    unexpected = ", ".join(f"{k} {v}" for k, v in s["unexpectedByOutcome"].items()) or "none"
    return {
        "Scenario": d["name"],
        "Client / protocol": f"{c['client']} {c['httpVersion']}",
        "Target /s": f"{s['targetRate']:.0f}",
        "Achieved /s (completed)": f"{s['completedPerSecond']:.1f}",
        "Started /s": f"{s['startedPerSecond']:.1f}",
        "p50 / p95 / p99 / max ms (ok reads)": f"{lat['p50']:.0f} / {lat['p95']:.0f} / {lat['p99']:.0f} / {lat['max']:.0f}",
        "max ms (all outcomes)": f"{s['latencyAllMillis']['max']:.0f}",
        "Max open": str(s["openMax"]),
        "Late %": f"{s['latePercent']:.3f}",
        "Start lag p99 / p99.9 / max ms": f"{w['startLagMillis']['p99']} / {w['startLagMillis']['p999']} / {w['startLagMillis']['max']}",
        "Deferred": str(s["deferred"]),
        "Injected %": f"{s['injectedPercent']:.2f}",
        "Unexpected errors %": f"{s['unexpectedErrorPercent']:.3f} ({unexpected})",
        "App CPU % of 1 core avg / max (limit 200)": f"{s['cpuPercentApp']['avg']:.0f} / {s['cpuPercentApp']['max']:.0f}",
        "WireMock CPU % avg / max (limit 400)": f"{s['cpuPercentWiremock']['avg']:.0f} / {s['cpuPercentWiremock']['max']:.0f}",
        "PostgreSQL CPU % avg / max (limit 200)":
            f"{s['cpuPercentPostgres']['avg']:.0f} / {s['cpuPercentPostgres']['max']:.0f}" if s["cpuPercentPostgres"] else "-",
        "Heap used max MiB (of max)": f"{s['heapUsedMaxMiB']:.0f} (of {w['heapMaxBytes'] / 1048576:.0f})",
        "Heap floor slope MiB/min": f"{s['heapFloorSlopeMiBPerMinute']:.2f}" if s["heapFloorSlopeMiBPerMinute"] is not None else "n/a",
        "Connections ESTAB avg / max": f"{conns['avg']:.0f} / {conns['max']}",
        "TIME-WAIT max": str(s["timeWait"]["max"]),
        "Local ports in use max": str(s["localPortsInUse"]["max"]),
        "Pool pending max": str(s["poolPendingMax"]),
        "Responses by version": versions,
        "Platform threads max": str(s["platformThreadsMax"]),
        "GC pause max ms": str(s["gcPauseMaxMillis"]),
        "App CPU throttled ms total / max per 5 s":
            f"{s['appThrottledMillisTotal']:.0f} / {s['appThrottledMillisMaxPer5s']:.0f}" if "appThrottledMillisTotal" in s else "n/a",
        "Host load1 avg / max": f"{s['hostLoad1']['avg']:.1f} / {s['hostLoad1']['max']:.1f}",
        "Window s": f"{w['windowSeconds']:.0f}",
        "Keeps up": "yes" if s["verdict"]["keepsUp"] else
            "no (" + ", ".join(k for k, v in s["verdict"].items() if not v and k != "keepsUp") + ")",
    }


def table(runs):
    rows = [row(d) for d in runs]
    keys = list(rows[0].keys())
    lines = ["| Metric | " + " | ".join(r["Scenario"] for r in rows) + " |",
             "|---|" + "---|" * len(rows)]
    for k in keys[1:]:
        lines.append(f"| {k} | " + " | ".join(r[k] for r in rows) + " |")
    return "\n".join(lines)


if __name__ == "__main__":
    names = sys.argv[1:] or sorted(p.stem for p in (ROOT / "results").glob("s*.json"))
    print(table(load(names)))
