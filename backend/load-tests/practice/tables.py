"""Builds the Markdown tables of RESULTS.md from the k6 summaries in results/.

Every configuration ran three times: each cell is the mean of the runs, with the lowest and the
highest value in brackets, so the variability shows next to the result.
    python tables.py > tables.md
"""

import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

RESULTS = Path(__file__).parent / "results"


def load(pattern):
    runs = {}
    for path in sorted(RESULTS.glob(pattern)):
        runs[path.stem] = json.loads(path.read_text(encoding="utf-8"))["metrics"]
    return runs


def spread(values, digits=0):
    values = [v for v in values if v is not None]
    if not values:
        return "–"
    mean = statistics.mean(values)
    if len(values) == 1:
        return f"{mean:.{digits}f}"
    return f"{mean:.{digits}f} ({min(values):.{digits}f}–{max(values):.{digits}f})"


def value(metrics, name, stat):
    metric = metrics.get(name)
    return None if metric is None else metric["values"].get(stat)


def operations(metrics):
    found = []
    for name in metrics:
        match = re.fullmatch(r"http_req_duration\{operation:([^,}]+)\}", name)
        if match:
            found.append(match.group(1))
    return found


def per_operation_table(runs, seconds, order=None):
    rows = defaultdict(lambda: defaultdict(list))
    for metrics in runs.values():
        for op in operations(metrics):
            row = rows[op]
            row["p50"].append(value(metrics, f"http_req_duration{{operation:{op}}}", "med"))
            row["p95"].append(value(metrics, f"http_req_duration{{operation:{op}}}", "p(95)"))
            row["p99"].append(value(metrics, f"http_req_duration{{operation:{op}}}", "p(99)"))
            count = value(metrics, f"http_reqs{{operation:{op}}}", "count")
            row["rps"].append(None if count is None else count / seconds)
            row["errors"].append(100 * (value(metrics, f"http_req_failed{{operation:{op}}}", "rate") or 0))
    names = order or sorted(rows, key=lambda op: -statistics.mean(rows[op]["p95"]))
    lines = [
        "| Operation | p50 (ms) | p95 (ms) | p99 (ms) | Requests/s | Errors (%) |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for op in names:
        r = rows[op]
        lines.append(
            f"| {op} | {spread(r['p50'], 1)} | {spread(r['p95'], 1)} | {spread(r['p99'], 1)} "
            f"| {spread(r['rps'])} | {spread(r['errors'], 2)} |"
        )
    return "\n".join(lines)


def totals_table(groups):
    lines = [
        "| Configuration | p50 (ms) | p95 (ms) | p99 (ms) | Requests/s | Errors (%) | Dropped iterations |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for label, runs in groups:
        m = list(runs.values())
        lines.append(
            f"| {label} | {spread([value(x, 'http_req_duration', 'med') for x in m], 1)} "
            f"| {spread([value(x, 'http_req_duration', 'p(95)') for x in m], 1)} "
            f"| {spread([value(x, 'http_req_duration', 'p(99)') for x in m], 1)} "
            f"| {spread([value(x, 'http_reqs', 'rate') for x in m])} "
            f"| {spread([100 * (value(x, 'http_req_failed', 'rate') or 0) for x in m], 2)} "
            f"| {spread([value(x, 'dropped_iterations', 'count') or 0 for x in m])} |"
        )
    return "\n".join(lines)


def phase_table(runs):
    lines = [
        "| Operation | Phase | p50 (ms) | p95 (ms) | p99 (ms) | Requests | Errors (%) |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for op in ("create", "map"):
        for phase in ("before", "spike", "after"):
            key = f"{{operation:{op},phase:{phase}}}"
            m = [x for x in runs.values() if f"http_req_duration{key}" in x]
            if not m:
                continue
            lines.append(
                f"| {op} | {phase} | {spread([value(x, 'http_req_duration' + key, 'med') for x in m], 1)} "
                f"| {spread([value(x, 'http_req_duration' + key, 'p(95)') for x in m], 1)} "
                f"| {spread([value(x, 'http_req_duration' + key, 'p(99)') for x in m], 1)} "
                f"| {spread([value(x, 'http_reqs' + key, 'count') for x in m])} "
                f"| {spread([100 * (value(x, 'http_req_failed' + key, 'rate') or 0) for x in m], 2)} |"
            )
    return "\n".join(lines)


def main():
    out = []
    exp1 = load("exp1-r*.json")
    if exp1:
        out += ["## Experiment 1", per_operation_table(exp1, seconds=60), ""]
    groups = []
    for label, pattern in (("ramping-vus 10→100", "exp2-vus-r*.json"), ("ramping-arrival-rate", "exp2-ramping-rate-r*.json")):
        runs = load(pattern)
        if runs:
            groups.append((label, runs))
    rates = sorted({int(re.search(r"constant-(\d+)-", p.name).group(1)) for p in RESULTS.glob("exp2-constant-*-r*.json")})
    for rate in rates:
        groups.append((f"constant-arrival-rate {rate}/s", load(f"exp2-constant-{rate}-r*.json")))
    if groups:
        out += ["## Experiment 2", totals_table(groups), ""]
    for label, pattern in (("with 40 users on the map", "exp3-map40-r*.json"), ("without the map", "exp3-map0-r*.json")):
        runs = load(pattern)
        if runs:
            out += [f"## Experiment 3, {label}", phase_table(runs), totals_table([(label, runs)]), ""]
    sizes = sorted({p.name.split("-")[1] for p in RESULTS.glob("exp4-*-r*.json")}, key=lambda s: (len(s), s))
    for size in sizes:
        out += [f"## Experiment 4, {size}", per_operation_table(load(f"exp4-{size}-r*.json"), seconds=30), ""]
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
