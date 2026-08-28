#!/usr/bin/env python3
"""Generate the build.yml matrix from lines/ (single-branch-consolidation-plan.md §4).

Emits two GITHUB_OUTPUT lines: build_matrix (every line) and tier3_matrix (only lines whose
line.properties says tier3_client_gametests=true). Each entry carries {line, java, build_subdir}
where build_subdir is '' for the default line (build/) and '<line>/' otherwise (build/<line>/) —
matching gradle/line.gradle's per-line build dir. The matrix source is cross-pinned to lines/*
by LineMatrixContractTest (set-equality), so a new line dir cannot silently not-build.
"""
import json
import os
import sys

DEFAULT_LINE = "26.2"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LINES = os.path.join(ROOT, "lines")


def load_props(path):
    d = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def main():
    build, tier3 = [], []
    for line in sorted(os.listdir(LINES)):
        ldir = os.path.join(LINES, line)
        if not os.path.isdir(ldir):
            continue
        props = load_props(os.path.join(ldir, "line.properties"))
        env = load_props(os.path.join(ldir, "line.env"))
        java = env.get("LINE_JAVA_VERSION") or props.get("line_java_version")
        if not java:
            print(f"line {line}: missing java version", file=sys.stderr)
            sys.exit(1)
        entry = {"line": line, "java": java,
                 "build_subdir": "" if line == DEFAULT_LINE else f"{line}/"}
        build.append(entry)
        if props.get("tier3_client_gametests") == "true":
            tier3.append(entry)
    print("build_matrix=" + json.dumps(build))
    print("tier3_matrix=" + json.dumps(tier3))


if __name__ == "__main__":
    main()
