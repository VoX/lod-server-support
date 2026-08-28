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
    build, tier3, compile_only, release = [], [], [], []
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
        status = props.get("fold_status", "full")
        if status == "full":
            # Release-ready: full gate suite (T1/T2/paper/neoforge/release_check) + artifacts.
            build.append(entry)
            if props.get("tier3_client_gametests") == "true":
                tier3.append(entry)
            # Release-pipeline entry: the per-line publish facts inlined from line.env
            # (full-tier lines GATE the publish; best-effort lines run continue-on-error —
            # the tier fact rides `make_latest`/support decisions, kept out of this matrix
            # so the ordering doctrine stays in release.yml).
            release.append({
                "line": line, "java": java,
                "build_subdir": entry["build_subdir"],
                # The 26.x (official-namespace) lines are the gating tier; 1.21.x
                # (intermediary) are continue-on-error so their flakes never block the
                # 26.x release (single-branch-consolidation-plan.md §5).
                "gates": "true" if props.get("mapping_namespace") == "official" else "false",
                "ship_neoforge": env.get("LINE_SHIP_NEOFORGE", "false"),
                "mc_fabric": env.get("LINE_MC_FABRIC", ""),
                "mc_paper": env.get("LINE_MC_PAPER", ""),
                "mc_neoforge": env.get("LINE_MC_NEOFORGE", ""),
                "gv_fabric": env.get("LINE_GAME_VERSIONS_FABRIC", ""),
                "gv_paper": env.get("LINE_GAME_VERSIONS_PAPER", ""),
                "gv_neoforge": env.get("LINE_GAME_VERSIONS_NEOFORGE", ""),
                "paper_loaders": env.get("LINE_PAPER_LOADERS", ""),
                "neoforge_name": env.get("LINE_NEOFORGE_NAME", ""),
            })
        else:
            # build-only: the fold's build arms are proven (compile all loaders) but its
            # test tiers / goldens are not yet folded — CI compiles it so the axis stays
            # exercised, without gating on tests it cannot yet pass.
            compile_only.append(entry)
    return build, tier3, compile_only, release


def emit():
    build, tier3, compile_only, release = main()
    print("build_matrix=" + json.dumps(build))
    print("tier3_matrix=" + json.dumps(tier3))
    print("compile_matrix=" + json.dumps(compile_only))
    print("release_matrix=" + json.dumps(release))


def selftest():
    """Pin the invariants the release/CI depend on (this is the single point that decides
    per-line gating and publishing)."""
    build, tier3, compile_only, release = main()
    names = lambda xs: {e["line"] for e in xs}
    # A build-only line must NEVER enter the full-gate build or the release matrix.
    assert names(compile_only).isdisjoint(names(build)), "build-only line leaked into build_matrix"
    assert names(compile_only).isdisjoint(names(release)), "build-only line leaked into release_matrix"
    # Every release entry's `gates` derives from the namespace (official → gates).
    for e in release:
        props = load_props(os.path.join(LINES, e["line"], "line.properties"))
        want = "true" if props.get("mapping_namespace") == "official" else "false"
        assert e["gates"] == want, f"{e['line']} gates={e['gates']} but namespace implies {want}"
        # tier3 entries are a subset of build entries.
    assert names(tier3).issubset(names(build)), "tier3 line not in build_matrix"
    # The default line, if release-ready, has an empty build_subdir; others carry '<line>/'.
    for e in build + release + compile_only:
        want_sub = "" if e["line"] == DEFAULT_LINE else f"{e['line']}/"
        assert e["build_subdir"] == want_sub, f"{e['line']} build_subdir wrong"
    print(f"gen_matrix selftest OK: build={sorted(names(build))} "
          f"compile-only={sorted(names(compile_only))} release={sorted(names(release))}")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        emit()
