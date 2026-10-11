#!/usr/bin/env python3
"""Writes readable tables to the GitHub Actions job summary.

Usage:
  ci_summary.py junit  "<title>" <folder with Surefire/Failsafe XML reports>
  ci_summary.py jacoco "<title>" <path to jacoco.csv>

It never fails the job: if there are no reports, the summary says so.
"""
import csv
import glob
import os
import sys
import xml.etree.ElementTree as ET


def write(lines):
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    text = "\n".join(lines) + "\n"
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(text)
    print(text)


def junit(title, directory):
    files = sorted(glob.glob(os.path.join(directory, "TEST-*.xml")))
    if not files:
        write([f"### {title}", "", "No test reports found."])
        return
    total = failures = errors = skipped = 0
    failed = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            total += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            for case in suite.findall("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.append(f"{case.get('classname', '')}.{case.get('name', '')}")
    passed = total - failures - errors - skipped
    icon = "✅" if failures + errors == 0 else "❌"
    lines = [
        f"### {icon} {title}",
        "",
        "| Total | Passed | Failures | Errors | Skipped |",
        "|---|---|---|---|---|",
        f"| {total} | {passed} | {failures} | {errors} | {skipped} |",
    ]
    if failed:
        lines += ["", "**Failing tests:**", ""] + [f"- `{name}`" for name in failed[:50]]
    write(lines)


def jacoco(title, csv_path):
    if not os.path.isfile(csv_path):
        write([f"### {title}", "", "No coverage report was generated."])
        return
    counters = {"LINE": [0, 0], "BRANCH": [0, 0], "INSTRUCTION": [0, 0]}
    with open(csv_path, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            for name in counters:
                counters[name][0] += int(row.get(f"{name}_MISSED", 0) or 0)
                counters[name][1] += int(row.get(f"{name}_COVERED", 0) or 0)
    lines = [f"### {title}", "", "| Counter | Covered | Total | % |", "|---|---|---|---|"]
    for name, (missed, covered) in counters.items():
        total = missed + covered
        pct = f"{100 * covered / total:.1f} %" if total else "—"
        lines.append(f"| {name} | {covered} | {total} | {pct} |")
    write(lines)


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in {"junit", "jacoco"}:
        print(__doc__)
        return 0
    mode, title, path = sys.argv[1:]
    try:
        (junit if mode == "junit" else jacoco)(title, path)
    except Exception as exc:  # the summary must never break CI
        print(f"Could not write the summary: {exc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
