#!/usr/bin/env python3
"""release_check.py — gate the release artifacts before they ship.

Inspects the built release jars and the workflow/metadata that publishes them, asserting:
  * no dev-only code ships — Fabric excludes dev/vox/lss/benchmark/** (which also holds the
    soak driver); Paper's release shadowJar excludes dev/vox/lss/paper/soak/**;
  * the dev-only Paper soak jar (lss-paper-soak*.jar) never matches the release glob;
  * required content is present — fabric.mod.json / plugin.yml, the common classes, LICENSE;
  * version placeholders are expanded (no literal ${version} in plugin.yml / fabric.mod.json);
  * Paper keeps the paperweight-mappings-namespace: mojang manifest attr through the shadowJar;
  * release.yml's publish globs actually match the CI artifact names (and not the soak jar).

Run after a CI-style build:
  CI=true ./gradlew :fabric:build -x runClientGameTest :paper:shadowJar
  python3 scripts/release_check.py            # auto-discovers fabric/ + paper/ build/libs
  python3 scripts/release_check.py --selftest # synthetic-jar fixtures, no build needed

Exit nonzero if any violation is found. Stdlib only.
"""

import argparse
import fnmatch
import io
import json
import os
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

FABRIC_FORBIDDEN = "dev/vox/lss/benchmark/"        # benchmark + soak driver live here on Fabric
PAPER_FORBIDDEN = "dev/vox/lss/paper/soak/"
RELEASE_GLOBS = ("lod-server-support-fabric-*.jar", "lod-server-support-paper-*.jar")
SOAK_JAR_PREFIX = "lss-paper-soak"


def _names(jar):
    with zipfile.ZipFile(jar) as z:
        return z.namelist()


def _read(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8", "replace")


def _manifest(jar):
    try:
        return _read(jar, "META-INF/MANIFEST.MF")
    except KeyError:
        return ""


def _looks_unexpanded(text):
    return "${version}" in text or "${ version }" in text


def check_fabric_jar(jar, problems):
    names = _names(jar)
    base = os.path.basename(jar)
    leaked = [n for n in names if n.startswith(FABRIC_FORBIDDEN)]
    if leaked:
        problems.append(f"{base}: ships dev-only {FABRIC_FORBIDDEN} ({len(leaked)} entries, e.g. {leaked[0]})")
    if not any(n == "fabric.mod.json" for n in names):
        problems.append(f"{base}: missing fabric.mod.json")
    else:
        meta = _read(jar, "fabric.mod.json")
        if _looks_unexpanded(meta):
            problems.append(f"{base}: fabric.mod.json has an unexpanded ${{version}} placeholder")
        else:
            try:
                v = json.loads(meta).get("version", "")
                if not v or v == "${version}":
                    problems.append(f"{base}: fabric.mod.json version is empty/placeholder ({v!r})")
            except json.JSONDecodeError as e:
                problems.append(f"{base}: fabric.mod.json is not valid JSON ({e.msg})")
    if not any(n.startswith("dev/vox/lss/") and n.endswith(".class") for n in names):
        problems.append(f"{base}: contains no dev/vox/lss classes (empty build?)")
    if not any(n.startswith("LICENSE") for n in names):
        problems.append(f"{base}: LICENSE not bundled")


def check_paper_jar(jar, problems):
    names = _names(jar)
    base = os.path.basename(jar)
    leaked = [n for n in names if n.startswith(PAPER_FORBIDDEN)]
    if leaked:
        problems.append(f"{base}: ships dev-only {PAPER_FORBIDDEN} ({len(leaked)} entries, e.g. {leaked[0]})")
    if not any(n == "plugin.yml" for n in names):
        problems.append(f"{base}: missing plugin.yml")
    else:
        ymltext = _read(jar, "plugin.yml")
        if _looks_unexpanded(ymltext):
            problems.append(f"{base}: plugin.yml has an unexpanded ${{version}} placeholder")
    if not any(n.startswith("dev/vox/lss/common/") and n.endswith(".class") for n in names):
        problems.append(f"{base}: shaded jar missing the shared common/ classes")
    if "paperweight-mappings-namespace: mojang" not in _manifest(jar):
        problems.append(f"{base}: manifest lost 'paperweight-mappings-namespace: mojang' "
                        "(server will refuse to load remapped NMS)")


def check_glob_hygiene(problems, fabric_jars, paper_jars, soak_jars):
    """The dev-only soak jar must never be picked up by the Paper release glob; the CI-named
    release jars must be picked up by their globs (a publish that matches nothing fails CI)."""
    for sj in soak_jars:
        base = os.path.basename(sj)
        for glob in RELEASE_GLOBS:
            if fnmatch.fnmatch(base, glob):
                problems.append(f"{base}: dev soak jar MATCHES release glob {glob} — would be published")
    # Round-trip the CI artifact name format against the globs (HD-043).
    ci_fabric = "lod-server-support-fabric-0.4.0+26.1.2.jar"
    ci_paper = "lod-server-support-paper-0.4.0+26.1.2.jar"
    if not fnmatch.fnmatch(ci_fabric, RELEASE_GLOBS[0]):
        problems.append(f"CI fabric name {ci_fabric} does not match release glob {RELEASE_GLOBS[0]}")
    if not fnmatch.fnmatch(ci_paper, RELEASE_GLOBS[1]):
        problems.append(f"CI paper name {ci_paper} does not match release glob {RELEASE_GLOBS[1]}")
    if fnmatch.fnmatch(f"{SOAK_JAR_PREFIX}-0.4.0+26.1.2.jar", RELEASE_GLOBS[1]):
        problems.append("CI-named soak jar matches the Paper release glob")


def discover(problems):
    fab = _jars_in(os.path.join(ROOT, "fabric", "build", "libs"), "lod-server-support-fabric")
    pap = _jars_in(os.path.join(ROOT, "paper", "build", "libs"), "lod-server-support-paper")
    soak = _jars_in(os.path.join(ROOT, "paper", "build", "libs"), SOAK_JAR_PREFIX)
    if not fab and not pap:
        problems.append("no release jars found under fabric/ or paper/ build/libs — run a build first")
    for jar in fab:
        check_fabric_jar(jar, problems)
    for jar in pap:
        check_paper_jar(jar, problems)
    check_glob_hygiene(problems, fab, pap, soak)
    return fab, pap, soak


def _jars_in(d, prefix):
    if not os.path.isdir(d):
        return []
    return [os.path.join(d, n) for n in sorted(os.listdir(d))
            if n.startswith(prefix) and n.endswith(".jar")]


# ----------------------------------------------------------------------------- selftest

def _make_jar(path, entries, manifest=None):
    with zipfile.ZipFile(path, "w") as z:
        if manifest is not None:
            z.writestr("META-INF/MANIFEST.MF", manifest)
        for name, data in entries.items():
            z.writestr(name, data)


def _selftest():
    n = 0

    def check(cond, msg):
        nonlocal n
        assert cond, "selftest FAIL: " + msg
        n += 1

    with tempfile.TemporaryDirectory() as td:
        good_fab = os.path.join(td, "lod-server-support-fabric.jar")
        _make_jar(good_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(good_fab, p)
        check(p == [], f"clean fabric jar flagged: {p}")

        bad_fab = os.path.join(td, "bad-fabric.jar")
        _make_jar(bad_fab, {
            "fabric.mod.json": json.dumps({"version": "${version}"}),
            "dev/vox/lss/benchmark/SoakScenarioDriver.class": "x",  # leaked dev code
            "dev/vox/lss/LSSMod.class": "x",
        })
        p = []
        check_fabric_jar(bad_fab, p)
        check(any("benchmark" in m for m in p), "leaked benchmark package not caught")
        check(any("placeholder" in m for m in p), "unexpanded version not caught")
        check(any("LICENSE" in m for m in p), "missing LICENSE not caught")

        good_pap = os.path.join(td, "lod-server-support-paper.jar")
        _make_jar(good_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(good_pap, p)
        check(p == [], f"clean paper jar flagged: {p}")

        bad_pap = os.path.join(td, "bad-paper.jar")
        _make_jar(bad_pap, {
            "plugin.yml": "name: X\nversion: ${version}\n",
            "dev/vox/lss/paper/soak/PaperSoakScenarioDriver.class": "x",  # leaked soak code
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\n")  # missing mappings-namespace
        p = []
        check_paper_jar(bad_pap, p)
        check(any("soak" in m for m in p), "leaked soak package not caught")
        check(any("placeholder" in m for m in p), "paper unexpanded version not caught")
        check(any("mappings-namespace" in m for m in p), "lost mappings namespace not caught")

        # glob hygiene: a CI-named soak jar must not match the paper release glob
        p = []
        check_glob_hygiene(p, [], [], [os.path.join(td, "lss-paper-soak-0.4.0+26.1.2.jar")])
        check(p == [], f"clean glob hygiene flagged: {p}")
        p = []
        # a soak jar mis-named to look like a release artifact MUST be caught
        check_glob_hygiene(p, [], [], [os.path.join(td, "lod-server-support-paper-soaky.jar")])
        check(any("MATCHES release glob" in m for m in p), "mis-named soak jar not caught")

    print(f"release_check selftest OK: {n} cases")
    return 0


# ----------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(description="Gate release jars + publish metadata before shipping.")
    ap.add_argument("--selftest", action="store_true", help="run synthetic-fixture checks and exit")
    args = ap.parse_args(argv)
    if args.selftest:
        return _selftest()

    problems = []
    fab, pap, soak = discover(problems)
    print(f"release_check: fabric jars={len(fab)} paper jars={len(pap)} soak jars={len(soak)}")
    if problems:
        print(f"FAIL: {len(problems)} release problem(s):")
        for m in problems:
            print(f"  - {m}")
        return 1
    print("OK: release artifacts clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
