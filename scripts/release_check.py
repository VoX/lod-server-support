#!/usr/bin/env python3
"""release_check.py — gate the release artifacts before they ship.

Inspects the built release jars and the workflow/metadata that publishes them, asserting:
  * no dev-only code ships — Fabric excludes dev/vox/lss/benchmark/** (which also holds the
    soak driver); Paper's release shadowJar excludes dev/vox/lss/paper/soak/**; any future
    dev/vox/lss/common/{soak,benchmark}/** is forbidden on both platforms, and the scan
    recurses into Loom's nested Jar-in-Jar entries (META-INF/jars/*.jar — the Fabric jar
    ships common/ that way, invisible to a top-level-only namelist scan);
  * the dev-only Paper soak jar (lss-paper-soak*.jar) never matches the release glob;
  * required content is present — fabric.mod.json / plugin.yml, the common classes, LICENSE;
  * version placeholders are expanded (no literal ${version} in plugin.yml / fabric.mod.json);
  * Paper keeps the paperweight-mappings-namespace: mojang manifest attr through the shadowJar;
  * the local RELEASE_GLOBS artifact contract matches the CI artifact names (and not the
    soak jar) — NOTE: release.yml publishes only the LSS pair on EVERY line since v0.8.0;
    the VSS globs here still gate the locally built byte-copies (built, not published);
  * discovery is unambiguous — stale jars from earlier builds fail the run (or are excluded
    by an explicit --version), so a green pre-flight always validated the jar being tagged.

Run after a CI-style build:
  CI=true ./gradlew :fabric:build -x runClientGameTest :paper:shadowJar -Pmod_version=X.Y.Z
  python3 scripts/release_check.py --version X.Y.Z   # check exactly the release jars
  python3 scripts/release_check.py            # auto-discovers fabric/ + paper/ build/libs
  python3 scripts/release_check.py --selftest # synthetic-jar fixtures, no build needed

Exit nonzero if any violation is found. Stdlib only.
"""

import argparse
import fnmatch
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

FABRIC_FORBIDDEN = "dev/vox/lss/benchmark/"        # benchmark + soak driver live here on Fabric
PAPER_FORBIDDEN = "dev/vox/lss/paper/soak/"
# The NeoForge shadow jar flattens xplat+common; benchmark/ (excluded by shadowJar) and
# the lsstest gametest companion (its own source set, never packed) must stay absent.
NEOFORGE_FORBIDDEN = ("dev/vox/lss/benchmark/", "dev/vox/lss/neoforge/gametest/",
                      # the Sodium 0.8 config-API compile-only stubs (LSSConfigMenu
                      # NeoForge twin) — shipping them collides with Sodium's module
                      "net/caffeinemc/")
# Dev-only namespaces that would live in common/ (e.g. a deduped soak-driver twin): common
# ships on BOTH platforms — nested in the Fabric jar, shaded into the Paper jar.
COMMON_FORBIDDEN = ("dev/vox/lss/common/soak/", "dev/vox/lss/common/benchmark/")
# All four shipped artifacts: the LSS pair (Modrinth lKiXKLvv) and the Voxy Server Side pair
# (Modrinth voxy-server-side). The VSS jars are branded byte-copies of the LSS jars — same
# classes, mod id `lss` / plugin name LodServerSupport, so they get the IDENTICAL safety
# gate plus an identity guardrail (check_vss_*_identity). See docs/planning/ci-dual-publish.md.
# v0.11.0 release scope (user decision 2026-08-15): NeoForge SHIPS only on the
# 1.21.1 line. Families gated on SHIP_NEOFORGE (found jars still get full jar
# checks); mirrors .github/line.env LINE_SHIP_NEOFORGE — flip BOTH together.
SHIP_NEOFORGE = True
RELEASE_GLOBS = ("lod-server-support-fabric-*.jar", "lod-server-support-paper-*.jar",
                 "voxy-server-side-fabric-*.jar", "voxy-server-side-paper-*.jar") + ((
                 "lod-server-support-neoforge-*.jar",
                 "voxy-server-side-neoforge-*.jar") if SHIP_NEOFORGE else ())
CI_NAME_SUFFIX = "0.4.0+26.1.2.jar"  # a representative CI filename for glob round-tripping
# The Fabric jar's mapping namespace for THIS line: 26.x fabric-loom ships official-mapped
# jars; 1.21.x fabric-loom-remap ships intermediary. A forward merge swapping the loom
# plugin produces a right-named, gate-green jar in the WRONG namespace — unloadable on any
# real server of this line — so the manifest attribute is pinned like Paper's namespace.
FABRIC_MAPPING_NAMESPACE = "official"
SOAK_JAR_PREFIX = "lss-paper-soak"


def _names(jar):
    with zipfile.ZipFile(jar) as z:
        return z.namelist()


def _nested_jars(jar):
    """[(label, namelist)] for every jar nested inside (Loom Jar-in-Jar
    META-INF/jars/ AND NeoForge jarJar META-INF/jarjar/), recursively —
    the Fabric release jar ships common/ as META-INF/jars/common-*.jar, so a top-level
    namelist scan never sees its classes."""
    with zipfile.ZipFile(jar) as z:
        out = []
        for entry in z.namelist():
            if (entry.startswith(("META-INF/jars/", "META-INF/jarjar/"))
                    and entry.endswith(".jar")):
                out.extend(_walk_nested(entry, z.read(entry)))
        return out


def _walk_nested(label, data):
    out = []
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = z.namelist()
        out.append((label, names))
        for entry in names:
            if (entry.startswith(("META-INF/jars/", "META-INF/jarjar/"))
                    and entry.endswith(".jar")):
                out.extend(_walk_nested(f"{label}!{entry}", z.read(entry)))
    return out


def _scan_forbidden(jar, base, prefixes, problems):
    """Flag forbidden-prefix entries at the top level AND inside every nested jar."""
    try:
        nested = _nested_jars(jar)
    except zipfile.BadZipFile as e:
        problems.append(f"{base}: unreadable nested jar ({e})")
        nested = []
    for label, names in [(None, _names(jar))] + nested:
        leaked = [n for n in names if n.startswith(prefixes)]
        if leaked:
            where = base if label is None else f"{base}!{label}"
            problems.append(f"{where}: ships forbidden entries "
                            f"({len(leaked)} entries, e.g. {leaked[0]})")


def _read(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8", "replace")


def _read_raw(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry)


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
    _scan_forbidden(jar, base, (FABRIC_FORBIDDEN,) + COMMON_FORBIDDEN, problems)
    if not any(n == "fabric.mod.json" for n in names):
        problems.append(f"{base}: missing fabric.mod.json")
    if "assets/lss/lang/en_us.json" not in names:
        problems.append(f"{base}: missing assets/lss/lang/en_us.json (the options page's keys —"
                        " a lost resource copy ships raw keys with every unit gate green)")
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
    ns_match = re.search(r"^Fabric-Mapping-Namespace:\s*(\S+)", _manifest(jar), re.MULTILINE)
    got_ns = ns_match.group(1) if ns_match else "<absent>"
    if got_ns != FABRIC_MAPPING_NAMESPACE:
        problems.append(f"{base}: Fabric-Mapping-Namespace is {got_ns} — this line ships "
                        f"{FABRIC_MAPPING_NAMESPACE}-mapped jars (wrong/missing namespace means "
                        "the loom plugin flavor regressed and the jar cannot load on a real server)")
    # The Fabric jar ships common/ as nested Jar-in-Jar; a Loom include regression would
    # otherwise ship a jar with no shared classes, green on every other check.
    nested = _nested_jars(jar)
    if not any("/common-" in label or label.startswith("META-INF/jars/common")
               for label, _ in nested):
        problems.append(f"{base}: no nested common jar (META-INF/jars/common-*.jar) — "
                        "the Loom include of :common is broken")
    elif not any(any(n.startswith("dev/vox/lss/common/") and n.endswith(".class") for n in ns)
                 for _, ns in nested):
        problems.append(f"{base}: nested common jar carries no dev/vox/lss/common classes")


def check_paper_jar(jar, problems):
    names = _names(jar)
    base = os.path.basename(jar)
    _scan_forbidden(jar, base, (PAPER_FORBIDDEN,) + COMMON_FORBIDDEN, problems)
    if not any(n == "plugin.yml" for n in names):
        problems.append(f"{base}: missing plugin.yml")
    else:
        ymltext = _read(jar, "plugin.yml")
        if _looks_unexpanded(ymltext):
            problems.append(f"{base}: plugin.yml has an unexpanded ${{version}} placeholder")
        # Re-inverted 2026-08-01: Folia shipped its first MC 26.2 build (26.2-1, BETA), so
        # the flag is declared again and the single jar must keep serving Folia. A jar that
        # LOSES the flag silently stops loading on Folia servers, which is the failure this
        # now guards (the previous inversion guarded the opposite risk, when no 26.2 Folia
        # existed to load onto).
        if not re.search(r"^folia-supported:\s*true\s*$", ymltext, re.MULTILINE):
            problems.append(f"{base}: plugin.yml lost folia-supported: true — Folia servers "
                            "will refuse this jar")
    if not any(n.startswith("dev/vox/lss/common/") and n.endswith(".class") for n in names):
        problems.append(f"{base}: shaded jar missing the shared common/ classes")
    if "paperweight-mappings-namespace: mojang" not in _manifest(jar):
        problems.append(f"{base}: manifest lost 'paperweight-mappings-namespace: mojang' "
                        "(server will refuse to load remapped NMS)")


# LOD-store engine natives (plan §3): every release jar must carry the FULL supported
# matrix — a missing native silently degrades that platform's store to the containment
# latch (store-off), which is fail-safe but must be a deliberate choice, not a packaging
# regression. Keep in sync with fabric/build.gradle slimStoreDepJars + paper/build.gradle.
SQLITE_NATIVES = (
    "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so",
    "org/sqlite/native/Linux/aarch64/libsqlitejdbc.so",
    "org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so",
    "org/sqlite/native/Linux-Musl/aarch64/libsqlitejdbc.so",
    "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll",
    "org/sqlite/native/Windows/aarch64/sqlitejdbc.dll",
    "org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib",
    "org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib",
)
# zstd natives embed the version in the file name — match by directory prefix.
ZSTD_NATIVE_DIRS = (
    "linux/amd64/", "linux/aarch64/", "win/amd64/", "win/aarch64/",
    "darwin/x86_64/", "darwin/aarch64/",
)


def _check_sqlite_natives(base, where, names, problems):
    for native in SQLITE_NATIVES:
        if native not in names:
            problems.append(f"{base}: {where} is missing sqlite native {native} — "
                            "the store silently degrades to off on that platform")


def _check_zstd_natives(base, where, names, problems):
    for d in ZSTD_NATIVE_DIRS:
        if not any(n.startswith(d) and (n.endswith(".so") or n.endswith(".dll")
                                        or n.endswith(".dylib")) for n in names):
            problems.append(f"{base}: {where} is missing a zstd native under {d} — "
                            "the store silently degrades to off on that platform")


# Native roots the strip lists prune (mirrors the gradle strip lists). Any native FILE
# under these that is NOT in the kept matrix means the strip regressed and the full
# multi-platform payload (+~10 MB) is shipping — presence checks alone stay green then.
STORE_NATIVE_ROOTS = ("org/sqlite/native/", "linux/", "win/", "darwin/", "freebsd/", "aix/")


def _check_native_strip(base, where, names, problems):
    kept_dirs = tuple({n[:n.rfind("/") + 1] for n in SQLITE_NATIVES}) + ZSTD_NATIVE_DIRS
    stray = [n for n in names
             if any(n.startswith(r) for r in STORE_NATIVE_ROOTS)
             and (n.endswith(".so") or n.endswith(".dll") or n.endswith(".dylib")
                  or n.endswith(".jnilib"))
             and not any(n.startswith(d) for d in kept_dirs)]
    if stray:
        problems.append(f"{base}: {where} carries {len(stray)} native(s) outside the "
                        f"supported matrix (e.g. {stray[0]}) — the native strip regressed "
                        "and the full multi-platform payload is shipping")


def check_store_natives_fabric(jar, problems):
    """The Fabric jar nests native-stripped sqlite-jdbc/zstd-jni as Jar-in-Jar; the
    fabric.mod.json 'jars' entries and the matrix inside each nested jar are the ship gate."""
    base = os.path.basename(jar)
    nested = dict(_nested_jars(jar))
    sq = nested.get("META-INF/jars/sqlite-jdbc-slim.jar")
    zs = nested.get("META-INF/jars/zstd-jni-slim.jar")
    if sq is None or zs is None:
        problems.append(f"{base}: missing nested store dep jar(s) "
                        f"(sqlite={'ok' if sq else 'MISSING'}, zstd={'ok' if zs else 'MISSING'})")
        return
    try:
        declared = {j.get("file") for j in json.loads(_read(jar, "fabric.mod.json")).get("jars", [])}
    except (KeyError, json.JSONDecodeError):
        return  # check_fabric_jar already flags the descriptor
    for f in ("META-INF/jars/sqlite-jdbc-slim.jar", "META-INF/jars/zstd-jni-slim.jar"):
        if f not in declared:
            problems.append(f"{base}: fabric.mod.json 'jars' does not declare {f} — "
                            "the loader will never load it")
    # The CONVERSE (4-agent round R4): every nested jar must be declared. The 'jars'
    # array is hand-maintained while slimStoreDepJars emits one jar per RESOLVED
    # artifact — a future transitive dep would ship undeclared (Fabric Loader ignores
    # undeclared nested jars), fail only at class-load time on real servers, and every
    # localRuntime-classpath gate would stay green.
    actual = {n for n in _names(jar)
              if n.startswith("META-INF/jars/") and n.endswith(".jar")}
    undeclared = sorted(actual - declared)
    if undeclared:
        problems.append(f"{base}: nested jar(s) not declared in fabric.mod.json 'jars': "
                        f"{', '.join(undeclared)} — the loader silently ignores them")
    _check_sqlite_natives(base, "nested sqlite-jdbc-slim.jar", set(sq), problems)
    _check_zstd_natives(base, "nested zstd-jni-slim.jar", set(zs), problems)
    _check_native_strip(base, "nested sqlite-jdbc-slim.jar", set(sq), problems)
    _check_native_strip(base, "nested zstd-jni-slim.jar", set(zs), problems)


def check_store_natives_paper(jar, problems):
    """Paper shades the store deps flat (org.sqlite deliberately NOT relocated — relocation
    breaks its native loader); the matrix sits at the top level of the shadow jar."""
    base = os.path.basename(jar)
    names = set(_names(jar))
    if not any(n == "org/sqlite/JDBC.class" for n in names):
        problems.append(f"{base}: org/sqlite classes missing from the shadow jar")
        return
    if any(n.startswith("dev/vox/lss/") and "/sqlite/" in n for n in names):
        problems.append(f"{base}: org.sqlite appears RELOCATED — relocation breaks the "
                        "sqlite native loader; it must stay at org/sqlite")
    _check_sqlite_natives(base, "shadow jar", names, problems)
    _check_zstd_natives(base, "shadow jar", names, problems)
    _check_native_strip(base, "shadow jar", names, problems)


def check_store_natives_neoforge(jar, problems):
    """NeoForge nests sqlite as a STOCK jarJar library (neoforge-jarjar-sqlite-plan.md):
    FML then dedupes org.xerial.sqlitejdbc across mods, closing the P-1 module
    collision the flat shading had (two modules exporting org.sqlite.* is a JPMS
    ResolutionException beside any sqlite-nesting mod, e.g. the community Voxy
    NeoForge port). zstd-jni stays flat-shaded (no collision exists for it)."""
    base = os.path.basename(jar)
    names = set(_names(jar))
    flat_sqlite = sorted(n for n in names if n.startswith("org/sqlite/"))
    if flat_sqlite:
        problems.append(f"{base}: {len(flat_sqlite)} flat org/sqlite entries (e.g. "
                        f"{flat_sqlite[0]}) — the shade configuration regressed; flat "
                        "sqlite re-opens the jarjar module collision")
    # With no flat sqlite left to inspect, a relocate rule would rewrite only OUR
    # classes' references — invisible to entry scans, NoClassDefFoundError live. The
    # store class itself is the observable: it must still name sqlite unrelocated.
    store_cls = "dev/vox/lss/common/store/SqliteLodStore.class"
    if store_cls not in names:
        problems.append(f"{base}: missing {store_cls} — the store class left the jar")
    else:
        with zipfile.ZipFile(jar) as z:
            if b"org/sqlite/SQLiteDataSource" not in z.read(store_cls):
                problems.append(f"{base}: SqliteLodStore no longer references "
                                "org/sqlite/SQLiteDataSource — a relocate rule rewrote "
                                "our store classes; the stock nested jar cannot satisfy "
                                "relocated references")
    meta_path = "META-INF/jarjar/metadata.json"
    if meta_path not in names:
        problems.append(f"{base}: missing {meta_path} — sqlite must ride as a jarJar "
                        "nested library")
        return
    try:
        meta = json.loads(_read(jar, meta_path))
        jars_list = meta.get("jars") if isinstance(meta, dict) else None
        if not isinstance(jars_list, list):
            problems.append(f"{base}: {meta_path} has no 'jars' list")
            return
        entries = [e for e in jars_list
                   if isinstance(e, dict) and isinstance(e.get("identifier"), dict)
                   and e["identifier"].get("group") == "org.xerial"
                   and e["identifier"].get("artifact") == "sqlite-jdbc"]
    except (ValueError, AttributeError, TypeError) as e:
        problems.append(f"{base}: {meta_path} does not parse: {e}")
        return
    # Converse (the fabric nested check's analogue): every nested jar must be
    # DECLARED — FML ignores undeclared jars, so an extra one is dead weight that
    # LOOKS shipped and NoClassDefFounds at runtime.
    declared = {str(e.get("path", "")) for e in jars_list if isinstance(e, dict)}
    undeclared = sorted(n for n in names if n.startswith("META-INF/jarjar/")
                        and n.endswith(".jar") and n not in declared)
    if undeclared:
        problems.append(f"{base}: {len(undeclared)} nested jar(s) undeclared in jarjar "
                        f"metadata (e.g. {undeclared[0]}) — FML ignores undeclared jars")
    if len(entries) != 1:
        problems.append(f"{base}: expected exactly one org.xerial:sqlite-jdbc jarjar "
                        f"metadata entry, found {len(entries)}")
        return
    entry = entries[0]
    path = str(entry.get("path", ""))
    ver_obj = entry.get("version")
    ver = str(ver_obj.get("artifactVersion", "")) if isinstance(ver_obj, dict) else ""
    if not path.startswith("META-INF/jarjar/"):
        problems.append(f"{base}: jarjar path {path!r} sits outside META-INF/jarjar/ — "
                        "nested libraries must live where the dev-code scan walks")
        return
    if path not in names:
        problems.append(f"{base}: jarjar metadata points at {path!r} but the nested "
                        "sqlite jar is missing from the shadow jar")
        return
    if not ver or path.rsplit("/", 1)[-1] != f"sqlite-jdbc-{ver}.jar":
        problems.append(f"{base}: jarjar artifactVersion {ver!r} disagrees with the "
                        f"nested jar filename {path!r}")
    if ver:
        want_range = f"[{ver},{int(ver.split('.')[0]) + 1}.0.0.0)"
        got_range = str(ver_obj.get("range", "")) if isinstance(ver_obj, dict) else ""
        if got_range != want_range:
            problems.append(f"{base}: jarjar range {got_range!r} must be {want_range!r} "
                            "— a future-major copy must fail as a hard version "
                            "conflict, never win selection under our compiled API")
    try:
        with zipfile.ZipFile(jar) as z:
            with zipfile.ZipFile(io.BytesIO(z.read(path))) as nz:
                nested_names = set(nz.namelist())
    except zipfile.BadZipFile:
        problems.append(f"{base}: nested sqlite jar at {path} is not a readable zip")
        return
    _check_sqlite_natives(base, f"nested {path}", nested_names, problems)
    # Deliberately NO _check_native_strip on the nested jar: it must be the STOCK
    # artifact, every platform included — the same bytes other mods nest, so
    # jarjar's same-version tie-break can never land on a trimmed copy (the exact
    # inverse of the flat-jar strip rule). The three pins below make "stock" an
    # assertion, not a comment:
    if "org/sqlite/SQLiteDataSource.class" not in nested_names:
        problems.append(f"{base}: nested sqlite jar carries no org/sqlite/"
                        "SQLiteDataSource.class — a classes-less artifact silently "
                        "degrades the store to off")
    kept_dirs = tuple(sorted({n[:n.rfind("/") + 1] for n in SQLITE_NATIVES}))
    if not any(n.startswith("org/sqlite/native/") and not n.startswith(kept_dirs)
               for n in nested_names):
        problems.append(f"{base}: nested sqlite jar looks TRIMMED (no natives outside "
                        "the supported matrix) — it must be the stock artifact, "
                        "byte-identical to what other mods nest")
    if "META-INF/versions/9/module-info.class" not in nested_names:
        problems.append(f"{base}: nested sqlite jar lost its module-info — FML would "
                        "load it as an AUTOMATIC module (different module name) and "
                        "the org.xerial.sqlitejdbc dedupe stops working")
    if "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE" not in nested_names:
        problems.append(f"{base}: nested sqlite jar lost META-INF/maven/org.xerial/"
                        "sqlite-jdbc/LICENSE — THIRD-PARTY-NOTICES delegates the "
                        "Apache-2.0 text to that path")
    _check_zstd_natives(base, "shadow jar", names, problems)
    _check_native_strip(base, "shadow jar", names, problems)


def check_neoforge_jar(jar, problems):
    """The NeoForge shadow jar (N-2 as amended by neoforge-jarjar-sqlite-plan.md:
    Paper-style shading for common+zstd, sqlite nested via jarJar):
    descriptor + mixin/AT/services presence, dev-package exclusion, and shading
    hygiene (no MC/loader classes may leak into the flat jar)."""
    names = set(_names(jar))
    base = os.path.basename(jar)
    _scan_forbidden(jar, base, NEOFORGE_FORBIDDEN + COMMON_FORBIDDEN, problems)
    # Round-3 review: the gametest smoke runs off classes dirs, never this jar — a
    # careless shadowJar exclude of dev/vox/lss/neoforge/** would ship an
    # entrypoint-less jar with every other check green. Pin the wiring classes.
    for req in ("dev/vox/lss/neoforge/LSSNeoMod.class",
                "dev/vox/lss/platform/NeoForgeLoaderServices.class",
                "dev/vox/lss/platform/NeoForgeClientLoaderServices.class",
                # the Sodium 0.8+ walker the TOML modproperties key names — its absence
                # is the SILENT no-page failure (Sodium warn-and-skips a missing class)
                "dev/vox/lss/config/LSSConfigMenu.class"):
        if req not in names:
            problems.append(f"{base}: missing {req} — the NeoForge entrypoint/seam/page "
                            "wiring was excluded from the shadow jar")
    if "META-INF/neoforge.mods.toml" not in names:
        problems.append(f"{base}: missing META-INF/neoforge.mods.toml")
    else:
        toml = _read(jar, "META-INF/neoforge.mods.toml")
        if 'modId="lss"' not in toml:
            problems.append(f"{base}: neoforge mod id must stay 'lss' (the wire-compat contract)")
        if _looks_unexpanded(toml) or 'version="${mod_version}"' in toml:
            problems.append(f"{base}: neoforge.mods.toml has an unexpanded version template")
        if 'config="lss.neoforge.mixins.json"' not in toml:
            problems.append(f"{base}: neoforge.mods.toml lost the mixin config declaration "
                            "(accessors + save hook would silently never apply)")
        import re as _re
        logo = _re.search(r'(?m)^logoFile="([^"]+)"$', toml)
        if not logo:
            problems.append(f"{base}: neoforge.mods.toml has no logoFile — the mods "
                            "screen shows a blank icon (must match the fabric jar)")
        elif logo.group(1) not in names:
            problems.append(f"{base}: logoFile points at {logo.group(1)!r} but that "
                            "entry is not in the jar")
    for required, why in (("lss.neoforge.mixins.json", "mixin config"),
                          ("lss-sodium-legacy.mixins.json",
                           "legacy-Sodium options hook mixin config"),
                          ("assets/lss/lang/en_us.json",
                           "lang file (the legacy Sodium options page's keys — a lost"
                           " processResources copy ships raw keys)"),
                          ("META-INF/accesstransformer.cfg", "access transformer"),
                          ("META-INF/services/dev.vox.lss.platform.LoaderServices",
                           "LoaderServices ServiceLoader registration")):
        if required not in names:
            problems.append(f"{base}: missing {required} ({why})")
    if not any(n.startswith("dev/vox/lss/common/") and n.endswith(".class") for n in names):
        problems.append(f"{base}: shaded jar missing the shared common/ classes")
    for leak in ("net/minecraft/", "net/neoforged/"):
        if any(n.startswith(leak) and n.endswith(".class") for n in names):
            problems.append(f"{base}: {leak} classes leaked into the shadow jar — the shade "
                            "configuration must never include the MC/loader classpath")


# Classes that legitimately exist ONLY in the fabric jar (per-loader wiring homes; the
# networking holders/renderer have same-FQN neoforge twins, so they are deliberately
# NOT listed — their absence from the neoforge jar would be real drift).
FABRIC_ONLY_CLASS_PREFIXES = (
    "dev/vox/lss/LSSMod", "dev/vox/lss/LSSClient",
    # The ModMenu switch stays fabric-only; the 0.8+ Sodium config-API walker is a
    # same-FQN NeoForge TWIN since 2026-08-26 (modproperties discovery) and rides the
    # presence check like the catalog, the probe, the legacy builder and RateSliderStops
    # (sodium-options-page-generations-plan.md §3).
    "dev/vox/lss/config/LSSModMenuIntegration",
    # The ModMenu "Configure" switch (fabric-only: NeoForge has no ModMenu; its
    # IConfigScreenFactory registration is the plan's Phase 4) — nested $1 rides the prefix.
    "dev/vox/lss/config/menu/SodiumConfigScreens",
    "dev/vox/lss/networking/client/LSSClientCommands",
    "dev/vox/lss/mixin/IntegratedServerLanHook",
    "dev/vox/lss/mixin/trace/MovementRejectHook",
    "dev/vox/lss/trace/MoveTraceBootstrap",
    "dev/vox/lss/platform/FabricLoaderServices",
    "dev/vox/lss/platform/FabricClientLoaderServices",
    # Same-FQN TWIN classes: each loader implements its own body behind the shared
    # outer name xplat compiles against, so their NESTED members legitimately differ
    # (the fabric renderer's Proxy/MountInstance vs the neoforge cut; the fabric
    # SessionConfigResponder alias). The OUTER class stays checked.
    "dev/vox/lss/networking/client/FarPlayerRenderer$",
    "dev/vox/lss/networking/client/LSSClientNetworking$",
    "dev/vox/lss/networking/server/LSSServerNetworking$",
    "dev/vox/lss/networking/LSSNetworking$",
    "dev/vox/lss/mixin/ChunkSaveDataHook$",
)


def check_cross_loader_classes(fabric_jar, neoforge_jar, problems):
    """The plan §4.2 cross-loader check, PRESENCE form (byte-equality would be brittle:
    the two compiles see different classpaths, so constant pools can differ while the
    source is identical — the shared xplat srcDir IS the byte-identity mechanism):
    every shared class the fabric jar ships must exist in the neoforge jar, so a
    shadowJar exclude or twin drift cannot silently drop shared surface."""
    base = os.path.basename(neoforge_jar)
    fab_names = set(_names(fabric_jar))
    neo_names = set(_names(neoforge_jar))
    missing = []
    for n in fab_names:
        if not (n.startswith("dev/vox/lss/") and n.endswith(".class")):
            continue
        # Boundary-exact matching (N-4 review): a bare prefix must match only the class
        # itself or its nested members — never a name EXTENSION (e.g. a future shared
        # MoveTraceBootstrapCommon must not ride the MoveTraceBootstrap allowlist row).
        if any(n.startswith(pref) if pref.endswith("$")
               else (n == pref + ".class" or n.startswith(pref + "$"))
               for pref in FABRIC_ONLY_CLASS_PREFIXES):
            continue
        if n not in neo_names:
            missing.append(n)
    # common/ rides NESTED in the fabric jar (META-INF/jars/common-*.jar) but FLAT in
    # the neoforge shade (N-4 review): without this walk a shadowJar exclude slimming a
    # common subpackage would ship a NoClassDefFoundError jar with every check green.
    for label, nested_names in _nested_jars(fabric_jar):
        if "common-" not in label:
            continue
        for n in nested_names:
            if (n.startswith("dev/vox/lss/common/") and n.endswith(".class")
                    and n not in neo_names):
                missing.append(f"{label}!{n}")
    if missing:
        problems.append(f"{base}: shared classes present in the fabric jar but missing here "
                        f"({len(missing)}): {sorted(missing)[:5]} — a shadowJar exclude or "
                        "twin drift dropped shared surface")


def check_vss_neoforge_identity(jar, problems):
    """The VSS neoforge rebrand: display fields flip, the wire identity does not."""
    base = os.path.basename(jar)
    try:
        toml = _read(jar, "META-INF/neoforge.mods.toml")
    except KeyError:
        return  # check_neoforge_jar already flagged the missing descriptor
    if 'modId="lss"' not in toml:
        problems.append(f"{base}: vss neoforge mod id must stay 'lss' (wire identity)")
    if 'displayName="Voxy Server Side"' not in toml:
        problems.append(f"{base}: vss neoforge displayName must be 'Voxy Server Side'")
    if "Xantha" not in toml:
        problems.append(f"{base}: vss neoforge authors must include Xantha")


def check_wire_identity_neoforge(lss_jar, vss_jar, problems):
    """The VSS repackage must byte-copy every dev/vox/lss CLASS entry (flat shaded jar,
    so the class set IS the wire surface — the Paper sha256-digest check's sibling;
    was CRC32 until the N-4 review, which is weaker than the byte proof it claimed)."""
    base = os.path.basename(vss_jar)
    if _class_digest(lss_jar) != _class_digest(vss_jar):
        problems.append(f"{base}: dev/vox/lss/**.class bytes differ from the LSS neoforge "
                        "jar — the repackage must be a byte-copy rebrand (wire identity)")


VSS_NEOFORGE_REBRAND_KEYS = ("displayName=", "authors=", "description=",
                             "issueTrackerURL=", "logoFile=")


def check_vss_pair_neoforge(lss_jar, vss_jar, problems):
    """The TOML pair diff (N-4 review): the VSS rebrand's line-anchored replaceFirst
    rewrites can silently corrupt a reflowed multi-line TOML construct (orphaned
    continuation text + a dangling triple-quote = an unloadable jar) while every
    substring-presence check stays green. Pin the exact rewrite shape: identical line
    COUNT, and every line byte-equal except the four branding keys."""
    vbase = os.path.basename(vss_jar)
    try:
        lss = _read(lss_jar, "META-INF/neoforge.mods.toml").splitlines()
        vss = _read(vss_jar, "META-INF/neoforge.mods.toml").splitlines()
    except KeyError:
        return  # the per-jar checks already flagged the missing descriptor
    if len(lss) != len(vss):
        problems.append(f"{vbase}: neoforge.mods.toml line count differs from the LSS jar "
                        f"({len(vss)} vs {len(lss)}) — the rebrand must be line-for-line "
                        "(a replaceFirst against a reflowed construct corrupts the TOML)")
        return
    for i, (a, b) in enumerate(zip(lss, vss), 1):
        if a == b:
            continue
        if not any(a.lstrip().startswith(k) and b.lstrip().startswith(k)
                   for k in VSS_NEOFORGE_REBRAND_KEYS):
            problems.append(f"{vbase}: neoforge.mods.toml line {i} differs outside the "
                            f"branding keys {VSS_NEOFORGE_REBRAND_KEYS}: {b!r}")
    _check_vss_lang_rebrand(lss_jar, vss_jar, vbase, problems)


def check_third_party_notices(jar, is_fabric, problems):
    """C6 (review-fixes round): the release jars redistribute two third-party native
    binaries (sqlite-jdbc, zstd-jni); the notices file shipped only by grace of a
    build.gradle resources line — a resources refactor dropped it with every gate
    green. Presence + non-empty at the top level of every release jar, and the Fabric
    nested sqlite-jdbc-slim.jar must retain its in-jar Apache-2.0 text (the slim
    repack must not strip it — the notices file DELEGATES sqlite's license to it)."""
    base = os.path.basename(jar)
    names = set(_names(jar))
    if "THIRD-PARTY-NOTICES" not in names:
        problems.append(f"{base}: THIRD-PARTY-NOTICES missing — the jar redistributes "
                        "sqlite-jdbc/zstd-jni binaries and must carry their notices")
        return
    if len(_read_raw(jar, "THIRD-PARTY-NOTICES")) < 100:
        problems.append(f"{base}: THIRD-PARTY-NOTICES is (near-)empty")
    if is_fabric:
        nested = dict(_nested_jars(jar))
        sq = nested.get("META-INF/jars/sqlite-jdbc-slim.jar")
        if sq is not None and "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE" not in set(sq):
            problems.append(f"{base}: nested sqlite-jdbc-slim.jar lost its in-jar LICENSE "
                            "— the slim task must not strip it (THIRD-PARTY-NOTICES "
                            "delegates sqlite's license text to it)")


def check_vss_fabric_identity(jar, problems):
    """The VSS Fabric jar is a branded byte-copy of the LSS jar. Rebranding may touch ONLY
    name/description/icon/contact — the mod `id` MUST stay `lss` (a forked id breaks
    wire+config interchangeability, the whole point of the dual distribution), and the jar
    MUST actually be rebranded (an un-rebranded LSS copy under the vss name is a mistake)."""
    base = os.path.basename(jar)
    try:
        meta = json.loads(_read(jar, "fabric.mod.json"))
    except (KeyError, json.JSONDecodeError):
        return  # check_fabric_jar already flags a missing/invalid descriptor
    if meta.get("id") != "lss":
        problems.append(f"{base}: vss Fabric jar mod id is {meta.get('id')!r}, must stay "
                        "'lss' — a forked id breaks LSS/VSS wire + config interchangeability")
    if meta.get("name") == "LOD Server Support":
        problems.append(f"{base}: vss Fabric jar is not rebranded "
                        "(name is still 'LOD Server Support')")


def check_vss_paper_identity(jar, problems):
    """The VSS Paper jar is a branded byte-copy of the LSS shadowJar. Since 2026-08-13
    (XANTHA's release patch) the plugin NAME rebrands to VoxyServerSide — the data folder
    deliberately forks to plugins/VoxyServerSide/ (a Paper-side jar swap starts a fresh
    config folder; the filename candidates only adopt within one folder). main/api-version/
    folia-supported stay verbatim — the identity + wire contract."""
    base = os.path.basename(jar)
    try:
        yml = _read(jar, "plugin.yml")
    except KeyError:
        return  # check_paper_jar already flags a missing plugin.yml
    if not re.search(r"^name:\s*VoxyServerSide\s*$", yml, re.MULTILINE):
        problems.append(f"{base}: vss Paper jar plugin name must be 'VoxyServerSide' "
                        "(the name rebrand is part of the VSS presentation since 2026-08-13)")


# fabric.mod.json fields the VSS rebrand MAY touch; everything else must be byte-equal to the
# LSS jar's descriptor (the build comments in fabric/build.gradle claim this invariant — this
# check enforces it, so a future vssJar edit can't silently fork entrypoints/mixins/depends).
VSS_FABRIC_ALLOWED_DIFF = {"name", "description", "icon", "contact", "authors"}


def _check_vss_lang_rebrand(lss_jar, vss_jar, vbase, problems):
    """Lang-value rebrand pin, shared by the fabric AND neoforge VSS pairs (the NeoForge
    jar gained the lang files with the legacy Sodium options page —
    sodium-options-page-generations-plan.md §3 — and its vssJar the same rewrite loop)."""
    # Lang-value rebrand pin (review-wave V-M2, generalized for the zh locales): the
    # vssJar rewrites EVERY assets/lss/lang/*.json entry's VALUES — a silent no-op ships
    # VSS Sodium pages reading "LOD Server Support"/"LSS" mid-sentence (the exact defect
    # the rewrite exists to fix). Skipped when the jar has no lang entries (synthetic
    # selftest fixtures); the byte-copy loop cannot drop entries. The LSS token match is
    # explicit ASCII lookarounds, not \b: Python's \b treats CJK as word chars, so a
    # CJK-adjacent "LSS" the Java rewrite WOULD rebrand would slip a \b-based checker.
    def _lang_names(jar):
        return {n for n in _names(jar)
                if n.startswith("assets/lss/lang/") and n.endswith(".json")}
    for LANG in sorted(_lang_names(lss_jar) | _lang_names(vss_jar)):
        if LANG in _names(lss_jar) and LANG not in _names(vss_jar):
            # Structurally impossible via the byte-copy loop; a hit means the resource
            # moved and the build's rewrite keys on the old path prefix.
            problems.append(f"{vbase}: {LANG} present in the LSS jar but missing from the"
                            " VSS jar — the lang rewrite and this pin key on that path")
            continue
        if LANG not in _names(vss_jar):
            continue
        try:
            vlang = json.loads(_read(vss_jar, LANG))
        except (KeyError, json.JSONDecodeError):
            problems.append(f"{vbase}: {LANG} is not valid JSON after the lang rebrand")
            vlang = {}
        # The rewrite is a parse -> per-VALUE rebrand -> re-serialize round trip: a dropped
        # or added entry would ship raw keys with every value-pin green — the key SETS
        # must match (implementation review).
        try:
            llang = json.loads(_read(lss_jar, LANG))
        except (KeyError, json.JSONDecodeError):
            llang = None
        if llang is not None and set(llang) != set(vlang):
            problems.append(f"{vbase}: {LANG} key set differs from the LSS jar — the lang "
                            "rebrand rewrites VALUES only")
        for k, v in vlang.items():
            sv = str(v)
            if "LOD Server Support" in sv or re.search(
                    r"(?<![A-Za-z0-9_])LSS(?![A-Za-z0-9_])", sv):
                problems.append(f"{vbase}: {LANG} value {k!r} still carries LSS branding "
                                "— the vssJar lang rewrite no-opped or missed it")

def check_vss_pair_fabric(lss_jar, vss_jar, problems):
    """Field-by-field diff of the two built descriptors: only branding fields may differ,
    and `name` MUST differ (a silent un-rebranded copy is a build regression). Also pins
    that the descriptor's icon path actually exists inside the vss jar."""
    vbase = os.path.basename(vss_jar)
    try:
        lmeta = json.loads(_read(lss_jar, "fabric.mod.json"))
        vmeta = json.loads(_read(vss_jar, "fabric.mod.json"))
    except (KeyError, json.JSONDecodeError):
        return  # per-jar checks already flag a missing/invalid descriptor
    for key in sorted(set(lmeta) | set(vmeta)):
        if key in VSS_FABRIC_ALLOWED_DIFF:
            continue
        if lmeta.get(key) != vmeta.get(key):
            problems.append(f"{vbase}: fabric.mod.json field {key!r} differs from the LSS jar "
                            f"— the VSS rebrand may only touch {sorted(VSS_FABRIC_ALLOWED_DIFF)}")
    if lmeta.get("name") == vmeta.get("name"):
        problems.append(f"{vbase}: fabric.mod.json 'name' equals the LSS jar's — not rebranded")
    icon = vmeta.get("icon")
    if icon and icon not in _names(vss_jar):
        problems.append(f"{vbase}: fabric.mod.json icon {icon!r} is not an entry in the jar")
    _check_vss_lang_rebrand(lss_jar, vss_jar, vbase, problems)


# plugin.yml lines that are the identity + wire contract: the VSS rebrand must leave every
# one of them byte-identical to the LSS jar. `main:` also pins the package/class names.
# `name:` LEFT this list 2026-08-13 (XANTHA's release patch): the VSS plugin presents as
# VoxyServerSide — check_vss_paper_identity pins the rebranded value instead, and the
# data-folder fork it causes is documented there.
VSS_PAPER_IDENTITY_PREFIXES = ("main:", "api-version:", "folia-supported:", "version:")


def check_vss_pair_paper(lss_jar, vss_jar, problems):
    """The VSS Paper jar rebrands plugin.yml's DISPLAY + LOCAL-command surface only. Line
    count must be unchanged (every rewrite is in-place). The identity/wire lines
    (main/api-version/folia; name rebrands since 2026-08-13) must be byte-identical. And the rebrand must actually have
    happened: the VSS jar carries vsslod / vss.admin / the Voxy description and NONE of the
    LSS tokens; the LSS jar carries the LSS tokens. The command name + permission node are
    LOCAL (never on the wire), so this rebrand does not affect LSS<->VSS compatibility."""
    vbase = os.path.basename(vss_jar)
    try:
        ltext = _read(lss_jar, "plugin.yml")
        vtext = _read(vss_jar, "plugin.yml")
    except KeyError:
        return  # per-jar checks already flag a missing plugin.yml
    llines, vlines = ltext.splitlines(), vtext.splitlines()
    if len(llines) != len(vlines):
        problems.append(f"{vbase}: plugin.yml line count differs from the LSS jar "
                        f"({len(llines)} vs {len(vlines)}) — the rebrand must rewrite lines "
                        "in place, never add or remove them")
        return
    if ltext == vtext:
        problems.append(f"{vbase}: plugin.yml is byte-identical to the LSS jar — the rebrand "
                        "silently no-opped (not rebranded)")
        return
    # Identity/wire lines must be untouched.
    for i, (a, b) in enumerate(zip(llines, vlines)):
        if any(a.lstrip().startswith(p) for p in VSS_PAPER_IDENTITY_PREFIXES) and a != b:
            problems.append(f"{vbase}: plugin.yml identity line {a.strip()!r} was rebranded "
                            "— name/main/api-version/folia are the identity + wire contract "
                            "and must stay verbatim")
    # The two top-level (column-0) display lines must each actually differ — their rewrites
    # are replaceFirst calls that fail SILENT on source-shape drift, and the token checks
    # below don't cover them (the website URL is in neither token list, and "Voxy Server
    # Side" is satisfied by the command description). A no-op here ships a VSS jar linking to
    # / described as the LSS project.
    for prefix in ("description:", "website:"):
        lln = next((ln for ln in llines if ln.startswith(prefix)), None)
        vln = next((ln for ln in vlines if ln.startswith(prefix)), None)
        if lln is not None and vln is not None and lln == vln:
            problems.append(f"{vbase}: plugin.yml top-level {prefix!r} line was not rebranded "
                            "(replaceFirst silently no-opped) — VSS would show the LSS "
                            "description / Modrinth link")
    # The rebrand must have swapped every LSS token for its VSS counterpart.
    # lss.farplayers.hidden is deliberately NOT in this list: BOTH brand spellings ship
    # in BOTH jars since 2026-08-13 (Bukkit's undeclared-node op default made the rename
    # + cross-brand enforcement silently hide ops) — see plugin.yml + the vssJar comment.
    for tok in ("lsslod", "lss.admin",
                "LOD Server Support admin", "Access to LSS admin"):
        if tok not in ltext:
            problems.append(f"{vbase}: LSS plugin.yml is missing expected token {tok!r} "
                            "(source shape changed — the VSS rewrite may silently no-op)")
        if tok in vtext:
            problems.append(f"{vbase}: VSS plugin.yml still contains LSS token {tok!r} "
                            "— the rebrand rewrite no-opped")
    for tok in ("vsslod", "vss.admin", "vss.farplayers.hidden", "Voxy Server Side"):
        if tok not in vtext:
            problems.append(f"{vbase}: VSS plugin.yml is missing expected VSS token {tok!r} "
                            "— the rebrand did not fully apply")


# ---------------------------------------------------------------- brand.properties + wire

# Expected runtime-branding values per jar brand. The VSS repackage rewrites lss-brand.properties
# (the ONLY thing Brand.java reads); everything it changes is display + LOCAL command text.
_BRAND_LSS = {"shortName": "LSS", "displayName": "LOD Server Support",
              "clientCommand": "lss", "serverCommand": "lsslod"}
_BRAND_VSS = {"shortName": "VSS", "displayName": "Voxy Server Side",
              "clientCommand": "vss", "serverCommand": "vsslod"}


def _brand_props(jar):
    props = {}
    for line in _read(jar, "lss-brand.properties").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def check_brand_properties(jar, expected, problems):
    """lss-brand.properties (both platforms) must carry exactly this brand's values — a
    mismatch means the repackage's rewrite drifted or no-opped."""
    base = os.path.basename(jar)
    try:
        props = _brand_props(jar)
    except KeyError:
        problems.append(f"{base}: no lss-brand.properties (Brand.java would fall back to LSS defaults)")
        return
    for key, want in expected.items():
        if props.get(key) != want:
            problems.append(f"{base}: lss-brand.properties {key}={props.get(key)!r}, expected {want!r}")


def check_wire_identity_fabric(lss_jar, vss_jar, problems):
    """The strongest LSS<->VSS wire-compat pin: the nested common jar (which holds every
    channel id, the protocol version, and all payload codecs) must be byte-for-byte identical
    between the LSS and VSS Fabric jars. If they match, the two brands are provably wire-equal."""
    vbase = os.path.basename(vss_jar)
    lname = next((n for n in _names(lss_jar) if re.search(r"META-INF/jars/common-.*\.jar$", n)), None)
    vname = next((n for n in _names(vss_jar) if re.search(r"META-INF/jars/common-.*\.jar$", n)), None)
    if lname is None or vname is None:
        problems.append(f"{vbase}: cannot locate the nested common jar in the LSS/VSS pair "
                        "— wire-identity unverifiable")
        return
    lsha = hashlib.sha256(_read_raw(lss_jar, lname)).hexdigest()
    vsha = hashlib.sha256(_read_raw(vss_jar, vname)).hexdigest()
    if lsha != vsha:
        problems.append(f"{vbase}: nested common jar differs from the LSS jar (sha {vsha[:12]} "
                        f"vs {lsha[:12]}) — branding must NEVER touch common (it carries the "
                        "wire: channels, protocol, payloads); LSS<->VSS compatibility is broken")


def _class_digest(jar):
    """sha256 over the sorted (name, bytes) of every dev/vox/lss/**.class entry — the code
    that determines wire behavior. Order-independent, content-exact."""
    h = hashlib.sha256()
    with zipfile.ZipFile(jar) as z:
        for name in sorted(n for n in z.namelist()
                           if n.startswith("dev/vox/lss/") and n.endswith(".class")):
            h.update(name.encode("utf-8"))
            h.update(b"\0")
            h.update(z.read(name))
    return h.hexdigest()


def check_wire_identity_paper(lss_jar, vss_jar, problems):
    """The Paper twin of check_wire_identity_fabric. Paper shades common FLAT (top-level
    dev/vox/lss/common/*.class), so there is no nested jar to hash — instead compare the
    class bytes of the whole dev/vox/lss/** tree between the LSS and VSS Paper jars. The VSS
    repackage rewrites only plugin.yml + lss-brand.properties; every class (common + paper,
    incl. the wire codecs) must be byte-identical, or branding leaked into behavior."""
    vbase = os.path.basename(vss_jar)
    ldig = _class_digest(lss_jar)
    vdig = _class_digest(vss_jar)
    if ldig != vdig:
        problems.append(f"{vbase}: dev/vox/lss/**.class bytes differ from the LSS jar "
                        f"(digest {vdig[:12]} vs {ldig[:12]}) — the VSS Paper repackage must "
                        "rewrite ONLY plugin.yml + lss-brand.properties; a changed class means "
                        "branding touched behavior (wire compat / identity at risk)")


def _vss_counterpart(vss_jar, lss_jars, vss_prefix, lss_prefix):
    """The LSS jar this vss jar was repackaged from: same filename with the prefix swapped
    (the version suffix, CI or local, is shared by construction in the vssJar tasks)."""
    want = os.path.basename(vss_jar).replace(vss_prefix, lss_prefix, 1)
    for j in lss_jars:
        if os.path.basename(j) == want:
            return j
    return None


def check_glob_hygiene(problems, soak_jars):
    """The dev-only soak jar must never be picked up by a release glob; every CI-named release
    jar (all six brand/platform combinations) must be picked up by exactly one release glob (a
    publish that matches nothing fails CI)."""
    for sj in soak_jars:
        base = os.path.basename(sj)
        for glob in RELEASE_GLOBS:
            if fnmatch.fnmatch(base, glob):
                problems.append(f"{base}: dev soak jar MATCHES release glob {glob} — would be published")
    # Round-trip every CI artifact name format against the globs (HD-043). Each of the six
    # shipped prefixes must match one release glob; the soak jar must match none.
    shipped_prefixes = ("lod-server-support-fabric", "lod-server-support-paper",
                        "voxy-server-side-fabric", "voxy-server-side-paper") + ((
                        "lod-server-support-neoforge",
                        "voxy-server-side-neoforge") if SHIP_NEOFORGE else ())
    for prefix in shipped_prefixes:
        ci_name = f"{prefix}-{CI_NAME_SUFFIX}"
        if not any(fnmatch.fnmatch(ci_name, g) for g in RELEASE_GLOBS):
            problems.append(f"CI name {ci_name} matches no release glob")
    if any(fnmatch.fnmatch(f"{SOAK_JAR_PREFIX}-{CI_NAME_SUFFIX}", g) for g in RELEASE_GLOBS):
        problems.append("CI-named soak jar matches a release glob")


def discover(problems, expected_version=None, root=ROOT):
    fab_libs = os.path.join(root, "fabric", "build", "libs")
    pap_libs = os.path.join(root, "paper", "build", "libs")
    neo_libs = os.path.join(root, "neoforge", "build", "libs")
    # `voxy-server-side-*` and `lod-server-support-*` are disjoint prefixes, so neither
    # discovery list contaminates the other.
    fab = _jars_in(fab_libs, "lod-server-support-fabric")
    pap = _jars_in(pap_libs, "lod-server-support-paper")
    vfab = _jars_in(fab_libs, "voxy-server-side-fabric")
    vpap = _jars_in(pap_libs, "voxy-server-side-paper")
    neo = _jars_in(neo_libs, "lod-server-support-neoforge")
    vneo = _jars_in(neo_libs, "voxy-server-side-neoforge")
    soak = _jars_in(pap_libs, SOAK_JAR_PREFIX)
    # All six families must be present — a release ships all six, and a missing family
    # (e.g. the vssJar finalizer silently unwired) must fail the gate, not shrink it.
    required = [(fab, "lod-server-support-fabric", "run :fabric:build"),
                (pap, "lod-server-support-paper", "run :paper:shadowJar"),
                (vfab, "voxy-server-side-fabric", "the fabric vssJar task did not run"),
                (vpap, "voxy-server-side-paper", "the paper vssJar finalizer did not run")]
    if SHIP_NEOFORGE:
        required += [(neo, "lod-server-support-neoforge", "run :neoforge:build"),
                     (vneo, "voxy-server-side-neoforge", "the neoforge vssJar task did not run")]
    for jars, what, hint in required:
        if not jars:
            problems.append(f"no {what} jar found in build/libs — {hint}")
    if expected_version:
        # A release ships all four; each must exist at the tag version — pinned to THIS
        # line's minecraft_version when known: on a multi-line repo the same mod_version
        # legitimately exists per release line, so version pinning alone could select a
        # stale other-line jar (0.7.3+26.2 beside 0.7.3+26.1.2) and green-light shipping it.
        mc = _minecraft_version(root)
        if mc is None:
            # Fail CLOSED: without the line's minecraft_version the mc pin silently reverts
            # to any-suffix matching — the exact multi-line stale-jar hole it exists to close.
            problems.append("cannot read minecraft_version from gradle.properties — refusing "
                            "to version-match release jars without the line pin")
        fab = _require_version(fab, "lod-server-support-fabric", expected_version, problems, mc=mc)
        pap = _require_version(pap, "lod-server-support-paper", expected_version, problems, mc=mc)
        vfab = _require_version(vfab, "voxy-server-side-fabric", expected_version, problems, mc=mc)
        vpap = _require_version(vpap, "voxy-server-side-paper", expected_version, problems, mc=mc)
        if SHIP_NEOFORGE:
            neo = _require_version(neo, "lod-server-support-neoforge", expected_version, problems, mc=mc)
            vneo = _require_version(vneo, "voxy-server-side-neoforge", expected_version, problems, mc=mc)
        # With --version the ambiguity guard is off, but release.yml's upload globs are
        # greedy: any OTHER versioned jar sitting beside the selected one would be attached
        # to the release too. Flag them (the suffixless local dev names stay tolerated).
        greedy = [(fab, _jars_in(fab_libs, "lod-server-support-fabric"), "lod-server-support-fabric"),
                  (pap, _jars_in(pap_libs, "lod-server-support-paper"), "lod-server-support-paper"),
                  (vfab, _jars_in(fab_libs, "voxy-server-side-fabric"), "voxy-server-side-fabric"),
                  (vpap, _jars_in(pap_libs, "voxy-server-side-paper"), "voxy-server-side-paper")]
        if SHIP_NEOFORGE:
            greedy += [(neo, _jars_in(neo_libs, "lod-server-support-neoforge"), "lod-server-support-neoforge"),
                       (vneo, _jars_in(neo_libs, "voxy-server-side-neoforge"), "voxy-server-side-neoforge")]
        for sel, allj, prefix in greedy:
            stale = [os.path.basename(j) for j in allj
                     if j not in sel and os.path.basename(j) != f"{prefix}.jar"]
            if sel and stale:
                problems.append(f"{prefix}: stale versioned jar(s) beside the release build: "
                                f"{stale} — delete them (release.yml globs would attach them)")
    else:
        _flag_ambiguous(fab, "lod-server-support-fabric", problems)
        _flag_ambiguous(pap, "lod-server-support-paper", problems)
        _flag_ambiguous(vfab, "voxy-server-side-fabric", problems)
        _flag_ambiguous(vpap, "voxy-server-side-paper", problems)
        if SHIP_NEOFORGE:
            _flag_ambiguous(neo, "lod-server-support-neoforge", problems)
            _flag_ambiguous(vneo, "voxy-server-side-neoforge", problems)
    for jar in fab:
        check_fabric_jar(jar, problems)
        check_store_natives_fabric(jar, problems)
        check_third_party_notices(jar, True, problems)
    for jar in pap:
        check_paper_jar(jar, problems)
        check_store_natives_paper(jar, problems)
        check_third_party_notices(jar, False, problems)
    for jar in neo:
        check_neoforge_jar(jar, problems)
        # sqlite rides NESTED via jarjar; zstd stays flat (neoforge-jarjar-sqlite-plan.md).
        check_store_natives_neoforge(jar, problems)
        check_third_party_notices(jar, False, problems)
        if fab:
            check_cross_loader_classes(fab[0], jar, problems)
    for jar in vneo:
        check_neoforge_jar(jar, problems)
        check_store_natives_neoforge(jar, problems)
        check_third_party_notices(jar, False, problems)
        check_vss_neoforge_identity(jar, problems)
        check_brand_properties(jar, _BRAND_VSS, problems)
        src = _vss_counterpart(jar, neo, "voxy-server-side-neoforge", "lod-server-support-neoforge")
        if src is None:
            problems.append(f"{os.path.basename(jar)}: no matching lod-server-support-neoforge "
                            "jar to pair-verify against (stale vss jar?)")
        else:
            check_wire_identity_neoforge(src, jar, problems)
            check_vss_pair_neoforge(src, jar, problems)
    # The vss jars ship to real users → identical safety gate, plus the identity guardrail
    # that pins them as branded byte-copies (mod id `lss`; plugin name VoxyServerSide since
    # the 2026-08-13 rebrand),
    # plus a descriptor pair-diff against the LSS jar they were repackaged from (only the
    # branding fields may differ — and must). A vss jar without its LSS counterpart cannot
    # be pair-verified and is a failure: the repackage task guarantees the source jar exists.
    # LSS jars must carry the LSS branding values (a rewrite regression could flip them).
    for jar in fab + pap + neo:
        check_brand_properties(jar, _BRAND_LSS, problems)
    for jar in vfab:
        check_fabric_jar(jar, problems)
        check_store_natives_fabric(jar, problems)
        check_third_party_notices(jar, True, problems)
        check_vss_fabric_identity(jar, problems)
        check_brand_properties(jar, _BRAND_VSS, problems)
        src = _vss_counterpart(jar, fab, "voxy-server-side-fabric", "lod-server-support-fabric")
        if src is None:
            problems.append(f"{os.path.basename(jar)}: no matching lod-server-support-fabric "
                            "jar to pair-verify against (stale vss jar?)")
        else:
            check_vss_pair_fabric(src, jar, problems)
            check_wire_identity_fabric(src, jar, problems)
    for jar in vpap:
        check_paper_jar(jar, problems)
        check_store_natives_paper(jar, problems)
        check_third_party_notices(jar, False, problems)
        check_vss_paper_identity(jar, problems)
        check_brand_properties(jar, _BRAND_VSS, problems)
        src = _vss_counterpart(jar, pap, "voxy-server-side-paper", "lod-server-support-paper")
        if src is None:
            problems.append(f"{os.path.basename(jar)}: no matching lod-server-support-paper "
                            "jar to pair-verify against (stale vss jar?)")
        else:
            check_vss_pair_paper(src, jar, problems)
            check_wire_identity_paper(src, jar, problems)
    check_glob_hygiene(problems, soak)
    return fab, pap, vfab, vpap, neo, vneo, soak


def _jars_in(d, prefix):
    if not os.path.isdir(d):
        return []
    return [os.path.join(d, n) for n in sorted(os.listdir(d))
            if n.startswith(prefix) and n.endswith(".jar")]


def _minecraft_version(root=ROOT):
    """The line's minecraft_version from gradle.properties, or None if unreadable. Used to
    pin --version matching to the FULL CI jar name: support branches make the same
    mod_version exist on several MC lines at once, so `-{version}+` alone is ambiguous."""
    try:
        with open(os.path.join(root, "gradle.properties"), encoding="utf-8") as fh:
            for line in fh:
                if line.strip().startswith("minecraft_version="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def _require_version(jars, prefix, version, problems, mc=None):
    """Restrict checking to the exact release jar for `version`; a missing jar is a failure
    — otherwise a stale jar from an earlier build gets validated in its place and the
    pre-flight green-lights code that was never built. `prefix` is the full jar base name
    (e.g. `lod-server-support-fabric` or `voxy-server-side-paper`). When `mc` is known the
    match requires the full `-{version}+{mc}.jar` CI name, so a stale jar of the SAME
    version from ANOTHER release line (0.7.3+26.2 vs 0.7.3+26.1.2) can never be selected;
    mc=None keeps the legacy any-suffix behavior (single-line repos, selftest fixtures)."""
    want_exact = f"{prefix}-{version}.jar"
    if mc:
        # Exact CI name only: the suffixless dev name never carries a version, and the bare
        # escape would re-admit a same-version jar of unknown provenance.
        want_versioned = f"{prefix}-{version}+{mc}.jar"
        matched = [j for j in jars if os.path.basename(j) == want_versioned]
    else:
        want_prefix = f"{prefix}-{version}+"
        matched = [j for j in jars
                   if os.path.basename(j).startswith(want_prefix)
                   or os.path.basename(j) == want_exact]
    if not matched:
        problems.append(f"{prefix}: no jar for version {version}"
                        + (f" on MC {mc}" if mc else "") + " in build/libs "
                        f"(found: {[os.path.basename(j) for j in jars] or 'none'}) — "
                        f"build with CI=true and -Pmod_version={version} first")
    return matched


def _flag_ambiguous(jars, platform, problems):
    """Without --version, more than one candidate jar means stale artifacts from earlier
    builds would be validated alongside (or instead of) the fresh one — refuse to guess."""
    if len(jars) > 1:
        problems.append(f"{platform}: {len(jars)} release jars in build/libs "
                        f"({[os.path.basename(j) for j in jars]}) — stale artifacts from "
                        f"earlier builds; run './gradlew clean' or pass --version")


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

    def _nested_common(version="0.4.0"):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("dev/vox/lss/common/PositionUtil.class", "x")
        return buf.getvalue()

    def _nested_sqlite(with_license=True):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("org/sqlite/JDBC.class", "x")
            if with_license:  # C6: the notices check pins this entry's survival
                z.writestr("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE", "Apache-2.0 ...")
            for native in SQLITE_NATIVES:
                z.writestr(native, "elf")
        return buf.getvalue()

    def _nested_zstd(missing_dir=None):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("com/github/luben/zstd/Zstd.class", "x")
            for d in ZSTD_NATIVE_DIRS:
                if d == missing_dir:
                    continue
                ext = ".dll" if d.startswith("win") else (".dylib" if d.startswith("darwin") else ".so")
                z.writestr(d + "libzstd-jni-1.5.7-3" + ext, "elf")
        return buf.getvalue()

    # Entries every schema-complete synthetic fabric release jar carries for the
    # store-native matrix check (the real jars nest these via slimStoreDepJars).
    STORE_FABRIC_ENTRIES = {
        "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
        "META-INF/jars/zstd-jni-slim.jar": _nested_zstd(),
    }
    # Mirrors the REAL fabric.mod.json: all three nested jars declared (the converse
    # check rejects any undeclared nested jar, so the fixture must declare common too).
    STORE_FABRIC_JARS_FIELD = [{"file": "META-INF/jars/sqlite-jdbc-slim.jar"},
                               {"file": "META-INF/jars/zstd-jni-slim.jar"},
                               {"file": "META-INF/jars/common-0.7.0.jar"}]

    def _store_paper_entries():
        out = {"org/sqlite/JDBC.class": "x", "com/github/luben/zstd/Zstd.class": "x"}
        for native in SQLITE_NATIVES:
            out[native] = "elf"
        for d in ZSTD_NATIVE_DIRS:
            ext = ".dll" if d.startswith("win") else (".dylib" if d.startswith("darwin") else ".so")
            out[d + "libzstd-jni-1.5.7-3" + ext] = "elf"
        return out

    def _store_neoforge_entries(group="org.xerial", drop_native=None, drop_nested_jar=False,
                                bad_version=False, drop_license=False, raw_meta=None,
                                stock=True, drop_metadata=False, undeclared_extra=False):
        # The jarjar nested shape (neoforge-jarjar-sqlite-plan.md): zstd flat, sqlite
        # as a synthesized stock-like nested jar + metadata.json. One VER literal so a
        # version edit cannot half-update the fixture.
        VER = "3.49.1.0"
        out = {"com/github/luben/zstd/Zstd.class": "x"}
        for d in ZSTD_NATIVE_DIRS:
            ext = ".dll" if d.startswith("win") else (".dylib" if d.startswith("darwin") else ".so")
            out[d + "libzstd-jni-1.5.7-3" + ext] = "elf"
        nested = {"org/sqlite/JDBC.class": "x",
                  "org/sqlite/SQLiteDataSource.class": "x"}
        if not drop_license:
            nested["META-INF/maven/org.xerial/sqlite-jdbc/LICENSE"] = "Apache-2.0"
        if stock:
            # The stockness discriminators: the MR module-info + at least one native
            # OUTSIDE the supported matrix (a trimmed repack has neither).
            nested["META-INF/versions/9/module-info.class"] = "x"
            nested["org/sqlite/native/FreeBSD/x86_64/libsqlitejdbc.so"] = "elf"
        for native in SQLITE_NATIVES:
            if native != drop_native:
                nested[native] = "elf"
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            for n, v in nested.items():
                z.writestr(n, v)
        if not drop_nested_jar:
            out[f"META-INF/jarjar/sqlite-jdbc-{VER}.jar"] = buf.getvalue()
        if undeclared_extra:
            rogue = io.BytesIO()
            with zipfile.ZipFile(rogue, "w") as z:
                z.writestr("x.txt", "x")
            out["META-INF/jarjar/rogue.jar"] = rogue.getvalue()
        meta_ver = "9.9.9.9" if bad_version else VER
        if raw_meta is not None:
            out["META-INF/jarjar/metadata.json"] = raw_meta
        elif not drop_metadata:
            out["META-INF/jarjar/metadata.json"] = json.dumps({"jars": [{
                "identifier": {"group": group, "artifact": "sqlite-jdbc"},
                "version": {"range": f"[{meta_ver},{int(meta_ver.split('.')[0]) + 1}.0.0.0)",
                            "artifactVersion": meta_ver},
                "path": f"META-INF/jarjar/sqlite-jdbc-{VER}.jar",
                "isObfuscated": False}]})
        return out

    with tempfile.TemporaryDirectory() as td:
        fab_manifest = "Manifest-Version: 1.0\nFabric-Mapping-Namespace: official\n"
        good_fab = os.path.join(td, "lod-server-support-fabric.jar")
        _make_jar(good_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "META-INF/jars/common-0.4.0.jar": _nested_common(),
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest=fab_manifest)
        p = []
        check_fabric_jar(good_fab, p)
        check(p == [], f"clean fabric jar flagged: {p}")

        # the loom-flavor regression: right name, wrong mapping namespace must be caught
        wrongns_fab = os.path.join(td, "wrongns-fabric.jar")
        _make_jar(wrongns_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "META-INF/jars/common-0.4.0.jar": _nested_common(),
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest="Manifest-Version: 1.0\nFabric-Mapping-Namespace: named\n")
        p = []
        check_fabric_jar(wrongns_fab, p)
        check(any("Fabric-Mapping-Namespace" in m for m in p),
              f"wrong fabric mapping namespace not caught: {p}")

        # a fabric jar with NO nested common jar means the Loom include of :common broke
        no_common_fab = os.path.join(td, "no-common-fabric.jar")
        _make_jar(no_common_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(no_common_fab, p)
        check(any("no nested common jar" in m for m in p),
              f"missing nested common jar not caught: {p}")

        bad_fab = os.path.join(td, "bad-fabric.jar")
        _make_jar(bad_fab, {
            "fabric.mod.json": json.dumps({"version": "${version}"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/benchmark/SoakScenarioDriver.class": "x",  # leaked dev code
            "dev/vox/lss/LSSMod.class": "x",
        })
        p = []
        check_fabric_jar(bad_fab, p)
        check(any("benchmark" in m for m in p), "leaked benchmark package not caught")
        check(any("placeholder" in m for m in p), "unexpanded version not caught")
        check(any("LICENSE" in m for m in p), "missing LICENSE not caught")

        # The real Fabric jar ships common/ as nested Jar-in-Jar (META-INF/jars/*.jar):
        # a clean nested common jar passes, dev code hidden inside it must be caught.
        nested_clean = io.BytesIO()
        with zipfile.ZipFile(nested_clean, "w") as z:
            z.writestr("dev/vox/lss/common/PositionUtil.class", "x")
        nested_fab = os.path.join(td, "lod-server-support-fabric-nested.jar")
        _make_jar(nested_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "META-INF/jars/common-0.4.0.jar": nested_clean.getvalue(),
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest=fab_manifest)
        p = []
        check_fabric_jar(nested_fab, p)
        check(p == [], f"clean nested common jar flagged: {p}")

        nested_dirty = io.BytesIO()
        with zipfile.ZipFile(nested_dirty, "w") as z:
            z.writestr("dev/vox/lss/common/PositionUtil.class", "x")
            z.writestr("dev/vox/lss/common/soak/SharedSoakDriver.class", "x")  # hidden leak
        leaky_fab = os.path.join(td, "leaky-nested-fabric.jar")
        _make_jar(leaky_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "META-INF/jars/common-0.4.0.jar": nested_dirty.getvalue(),
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(leaky_fab, p)
        check(any("common/soak" in m and "META-INF/jars" in m for m in p),
              f"dev code inside a nested jar not caught: {p}")

        good_pap = os.path.join(td, "lod-server-support-paper.jar")
        _make_jar(good_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\nfolia-supported: true\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(good_pap, p)
        check(p == [], f"clean paper jar flagged: {p}")

        # a paper jar LOSING folia-supported must be caught: Folia ships a 26.2 build since
        # 2026-07-28, so a jar without the flag silently stops loading on Folia servers
        # (re-inverted 2026-08-01 — the previous direction guarded the opposite risk, back
        # when no Folia 26.2 existed to load onto)
        foliaflag_pap = os.path.join(td, "foliaflag-paper.jar")
        _make_jar(foliaflag_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(foliaflag_pap, p)
        check(any("folia-supported" in m for m in p), "missing folia-supported flag not caught")

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

        # Paper shades common/ at the top level: a dev-only common namespace must be caught.
        common_leak_pap = os.path.join(td, "common-leak-paper.jar")
        _make_jar(common_leak_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "dev/vox/lss/common/benchmark/SharedBenchHook.class": "x",  # leaked dev code
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(common_leak_pap, p)
        check(any("common/benchmark" in m for m in p),
              f"dev code in a shaded common namespace not caught: {p}")

        # Discovery ambiguity: stale jars alongside the fresh one must fail without
        # --version, and --version must select exactly the requested release jar.
        p = []
        _flag_ambiguous(["a/lod-server-support-fabric.jar",
                         "a/lod-server-support-fabric-0.4.0+26.1.2.jar"], "fabric", p)
        check(any("stale artifacts" in m for m in p), "ambiguous multi-jar dir not flagged")
        p = []
        _flag_ambiguous(["a/lod-server-support-fabric-0.5.0+26.1.2.jar"], "fabric", p)
        check(p == [], f"single jar wrongly flagged as ambiguous: {p}")
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.4.0+26.1.2.jar",
                                "a/lod-server-support-fabric-0.5.0+26.1.2.jar"],
                               "lod-server-support-fabric", "0.5.0", p)
        check(p == [] and [os.path.basename(j) for j in got]
              == ["lod-server-support-fabric-0.5.0+26.1.2.jar"],
              f"--version did not select exactly the requested jar: {got} {p}")
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.4.0+26.1.2.jar"],
                               "lod-server-support-fabric", "0.5.0", p)
        check(got == [] and any("no jar for version 0.5.0" in m for m in p),
              f"missing requested version not caught: {got} {p}")
        # the vss prefix is version-pinned the same way, disjoint from the LSS prefix
        p = []
        got = _require_version(["a/voxy-server-side-fabric-0.7.0+26.2.jar"],
                               "voxy-server-side-fabric", "0.7.0", p)
        check(p == [] and [os.path.basename(j) for j in got]
              == ["voxy-server-side-fabric-0.7.0+26.2.jar"],
              f"vss --version did not select the vss jar: {got} {p}")

        # mc-pinned matching (support lines): the SAME mod_version exists on multiple
        # release lines, so a stale other-line jar of the right version must be rejected
        # when gradle.properties' minecraft_version is known.
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.7.3+26.2.jar",
                                "a/lod-server-support-fabric-0.7.3+26.1.2.jar"],
                               "lod-server-support-fabric", "0.7.3", p, mc="26.1.2")
        check(p == [] and [os.path.basename(j) for j in got]
              == ["lod-server-support-fabric-0.7.3+26.1.2.jar"],
              f"mc-pinned selection did not pick exactly this line's jar: {got} {p}")
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.7.3+26.2.jar"],
                               "lod-server-support-fabric", "0.7.3", p, mc="26.1.2")
        check(got == [] and any("no jar for version 0.7.3 on MC 26.1.2" in m for m in p),
              f"wrong-line stale jar passed the mc-pinned version gate: {got} {p}")
        # mc known: the bare suffixless escape must be gone too (exact CI name only)
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.7.3.jar"],
                               "lod-server-support-fabric", "0.7.3", p, mc="26.1.2")
        check(got == [], "suffixless same-version jar must not satisfy the mc-pinned gate")

        # ---- Voxy Server Side branded jars: full LSS gate + identity guardrail ----
        # A clean vss Fabric jar: rebranded name, but mod id STILL `lss` → passes both gates.
        good_vfab = os.path.join(td, "voxy-server-side-fabric.jar")
        _make_jar(good_vfab, {
            "fabric.mod.json": json.dumps({"id": "lss", "name": "Voxy Server Side",
                                           "version": "0.7.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "META-INF/jars/common-0.7.0.jar": _nested_common("0.7.0"),
            "assets/lss/icon-vss.png": "PNG",
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest=fab_manifest)
        p = []
        check_fabric_jar(good_vfab, p)
        check_vss_fabric_identity(good_vfab, p)
        check(p == [], f"clean vss fabric jar flagged: {p}")

        # A vss Fabric jar whose id was forked away from `lss` MUST fail — that silently
        # breaks LSS/VSS wire + config interchangeability.
        forked_vfab = os.path.join(td, "voxy-server-side-fabric-forked.jar")
        _make_jar(forked_vfab, {
            "fabric.mod.json": json.dumps({"id": "vss", "name": "Voxy Server Side",
                                           "version": "0.7.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_vss_fabric_identity(forked_vfab, p)
        check(any("must stay 'lss'" in m for m in p), f"forked vss id not caught: {p}")

        # A vss Fabric jar that was never actually rebranded MUST fail.
        unbranded_vfab = os.path.join(td, "voxy-server-side-fabric-unbranded.jar")
        _make_jar(unbranded_vfab, {
            "fabric.mod.json": json.dumps({"id": "lss", "name": "LOD Server Support",
                                           "version": "0.7.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_vss_fabric_identity(unbranded_vfab, p)
        check(any("not rebranded" in m for m in p), f"un-rebranded vss jar not caught: {p}")

        # A vss Fabric jar that leaked dev code MUST fail the shared gate too.
        leaky_vfab = os.path.join(td, "voxy-server-side-fabric-leaky.jar")
        _make_jar(leaky_vfab, {
            "fabric.mod.json": json.dumps({"id": "lss", "name": "Voxy Server Side",
                                           "version": "0.7.0"}),
            "assets/lss/lang/en_us.json": "{}",
            "dev/vox/lss/benchmark/BenchmarkHook.class": "x",  # leaked dev code
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(leaky_vfab, p)
        check(any("benchmark" in m for m in p), "vss jar dev-code leak not caught by shared gate")

        # A clean vss Paper jar: name VoxyServerSide (the 2026-08-13 rebrand) → passes.
        good_vpap = os.path.join(td, "voxy-server-side-paper.jar")
        _make_jar(good_vpap, {
            "plugin.yml": ("name: VoxyServerSide\nversion: 0.7.0\n"
                           "folia-supported: true\n"
                           "description: Render distant Voxy LODs on servers\n"),
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(good_vpap, p)
        check_vss_paper_identity(good_vpap, p)
        check(p == [], f"clean vss paper jar flagged: {p}")

        # A vss Paper jar that KEPT the LSS plugin name MUST fail — the rebrand silently
        # no-opped (the pre-2026-08-13 shape, now the regression direction).
        unrenamed_vpap = os.path.join(td, "voxy-server-side-paper-unrenamed.jar")
        _make_jar(unrenamed_vpap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.7.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_vss_paper_identity(unrenamed_vpap, p)
        check(any("must be 'VoxyServerSide'" in m for m in p),
              f"un-renamed vss plugin name not caught: {p}")

        # ---- VSS≡LSS pair checks: only branding fields may differ, and must ----
        pair_lss_fab = os.path.join(td, "pair-lss-fabric.jar")
        _make_jar(pair_lss_fab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "LOD Server Support", "description": "LSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon.png"}),
            "assets/lss/lang/en_us.json": "{}",
            "assets/lss/icon.png": "PNG",
            "assets/lss/lang/en_us.json": json.dumps(
                {"lss.config.page": "LOD Server Support", "lss.x": "LSS toggles"}),
            "assets/lss/lang/zh_cn.json": json.dumps(
                {"lss.x": "\u4f7f\u7528 LSS \u5f00\u5173"}),
        })
        pair_ok_vfab = os.path.join(td, "pair-ok-vss-fabric.jar")
        _make_jar(pair_ok_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/icon.png": "PNG",
            "assets/lss/icon-vss.png": "PNG2",
            "assets/lss/lang/en_us.json": json.dumps(
                {"lss.config.page": "Voxy Server Side", "lss.x": "VSS toggles"}),
            "assets/lss/lang/zh_cn.json": json.dumps(
                {"lss.x": "\u4f7f\u7528 VSS \u5f00\u5173"}),
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_ok_vfab, p)
        check(p == [], f"clean fabric pair flagged: {p}")

        # a vss jar whose NON-en_us locale kept LSS branding MUST fail (the zh
        # generalization's catch side — the old pin keyed on the en_us literal and a
        # clean en_us beside a missed zh_cn shipped LSS-branded Chinese pages)
        pair_zh_vfab = os.path.join(td, "pair-zhlang-vss-fabric.jar")
        _make_jar(pair_zh_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/lang/en_us.json": "{}",
            "assets/lss/icon.png": "PNG",
            "assets/lss/icon-vss.png": "PNG2",
            "assets/lss/lang/en_us.json": json.dumps(
                {"lss.config.page": "Voxy Server Side", "lss.x": "VSS toggles"}),
            "assets/lss/lang/zh_cn.json": json.dumps(
                {"lss.x": "\u4f7f\u7528 LSS \u5f00\u5173"}),
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_zh_vfab, p)
        check(any("zh_cn.json" in m and "still carries LSS branding" in m for m in p),
              f"un-rebranded zh_cn lang values not caught: {p}")

        # a vss jar whose lang VALUES kept the LSS branding MUST fail (the V-M2 pin's
        # catch side — a silently no-opped lang rewrite ships LSS-branded Sodium pages)
        pair_nolang_vfab = os.path.join(td, "pair-nolang-vss-fabric.jar")
        _make_jar(pair_nolang_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/lang/en_us.json": "{}",
            "assets/lss/icon.png": "PNG",
            "assets/lss/icon-vss.png": "PNG2",
            "assets/lss/lang/en_us.json": json.dumps(
                {"lss.config.page": "LOD Server Support", "lss.x": "LSS toggles"}),
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_nolang_vfab, p)
        check(any("still carries LSS branding" in m for m in p),
              f"un-rebranded vss lang values not caught: {p}")

        # a vss descriptor whose NON-branding field drifted (entrypoints fork) MUST fail
        pair_forked_vfab = os.path.join(td, "pair-forked-vss-fabric.jar")
        _make_jar(pair_forked_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.OtherMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/lang/en_us.json": "{}",
            "assets/lss/icon-vss.png": "PNG2",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_forked_vfab, p)
        check(any("'entrypoints' differs" in m for m in p),
              f"forked entrypoints not caught by pair check: {p}")

        # an un-rebranded pair (name equal) MUST fail
        pair_same_vfab = os.path.join(td, "pair-same-vss-fabric.jar")
        _make_jar(pair_same_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "LOD Server Support", "description": "LSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon.png"}),
            "assets/lss/lang/en_us.json": "{}",
            "assets/lss/icon.png": "PNG",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_same_vfab, p)
        check(any("not rebranded" in m for m in p), f"pair name-equal not caught: {p}")

        # a descriptor icon that points at a missing jar entry MUST fail
        pair_noicon_vfab = os.path.join(td, "pair-noicon-vss-fabric.jar")
        _make_jar(pair_noicon_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/lang/en_us.json": "{}",
            "assets/lss/icon.png": "PNG",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_noicon_vfab, p)
        check(any("not an entry in the jar" in m for m in p),
              f"missing icon entry not caught: {p}")

        LSS_PLUGIN_YML = ("name: LodServerSupport\nversion: '0.7.0'\n"
                          "main: dev.vox.lss.paper.LSSPaperPlugin\n"
                          "api-version: '26.2'\n"
                          "description: LSS plugin.\n"
                          "author: VoX\n"
                          "website: https://modrinth.com/plugin/lod-server-support\n"
                          "commands:\n  lsslod:\n"
                          "    description: LOD Server Support admin commands\n"
                          "    usage: /lsslod <stats|diag>\n"
                          "    permission: lss.admin\n"
                          "permissions:\n  lss.admin:\n"
                          "    description: Access to LSS admin commands\n"
                          "    default: op\n"
                          # BOTH brand spellings ship in BOTH jars (2026-08-13) — the
                          # undeclared-node op default made a single-brand rename unsafe.
                          "  lss.farplayers.hidden:\n"
                          "    description: hidden\n"
                          "    default: false\n"
                          "  vss.farplayers.hidden:\n"
                          "    description: hidden (VSS spelling)\n"
                          "    default: false\n")
        # Mirror vssJar's exact rewrites (2026-08-13 shape: name/author rebrand too; the
        # farplayers node is deliberately NOT renamed — both spellings ship in both jars).
        VSS_PLUGIN_YML = (LSS_PLUGIN_YML
            .replace("name: LodServerSupport", "name: VoxyServerSide", 1)
            .replace("description: LSS plugin.",
                     "description: Render distant Voxy LODs on servers", 1)
            .replace("author: VoX", "author: Xantha, VoX", 1)
            .replace("website: https://modrinth.com/plugin/lod-server-support",
                     "website: https://modrinth.com/plugin/voxy-server-side", 1)
            .replace("lsslod", "vsslod")
            .replace("LOD Server Support admin commands", "Voxy Server Side admin commands")
            .replace("Access to LSS admin commands", "Access to VSS admin commands")
            .replace("lss.admin", "vss.admin"))
        pair_lss_pap = os.path.join(td, "pair-lss-paper.jar")
        _make_jar(pair_lss_pap, {"plugin.yml": LSS_PLUGIN_YML})
        pair_ok_vpap = os.path.join(td, "pair-ok-vss-paper.jar")
        _make_jar(pair_ok_vpap, {"plugin.yml": VSS_PLUGIN_YML})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_ok_vpap, p)
        check(p == [], f"clean paper pair flagged: {p}")

        # a byte-identical plugin.yml means the rebrand rewrites silently no-opped
        pair_same_vpap = os.path.join(td, "pair-same-vss-paper.jar")
        _make_jar(pair_same_vpap, {"plugin.yml": LSS_PLUGIN_YML})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_same_vpap, p)
        check(any("silently no-opped" in m for m in p),
              f"paper no-op rewrite not caught: {p}")

        # an incomplete rebrand (command key left as lsslod) MUST fail both directions
        pair_partial_vpap = os.path.join(td, "pair-partial-vss-paper.jar")
        _make_jar(pair_partial_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace("vsslod", "lsslod")})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_partial_vpap, p)
        check(any("still contains LSS token 'lsslod'" in m for m in p)
              and any("missing expected VSS token 'vsslod'" in m for m in p),
              f"paper partial rebrand not caught: {p}")

        # rebranding an IDENTITY line (main: — the package/class contract) MUST fail
        pair_idname_vpap = os.path.join(td, "pair-idname-vss-paper.jar")
        _make_jar(pair_idname_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace(
            "main: dev.vox.lss.paper.LSSPaperPlugin",
            "main: dev.vox.vss.paper.VSSPaperPlugin", 1)})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_idname_vpap, p)
        check(any("identity line" in m for m in p),
              f"paper identity-line rebrand not caught: {p}")

        # the top-level website/description rewrites failing SILENT (command surface rebranded,
        # display lines left as LSS) MUST fail — the VSS jar would link to the LSS project
        pair_noweb_vpap = os.path.join(td, "pair-noweb-vss-paper.jar")
        _make_jar(pair_noweb_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace(
            "website: https://modrinth.com/plugin/voxy-server-side",
            "website: https://modrinth.com/plugin/lod-server-support", 1)})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_noweb_vpap, p)
        check(any("'website:' line was not rebranded" in m for m in p),
              f"paper website no-op not caught: {p}")
        pair_nodesc_vpap = os.path.join(td, "pair-nodesc-vss-paper.jar")
        _make_jar(pair_nodesc_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace(
            "description: Render distant Voxy LODs on servers",
            "description: LSS plugin.", 1)})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_nodesc_vpap, p)
        check(any("'description:' line was not rebranded" in m for m in p),
              f"paper description no-op not caught: {p}")

        # a line-count change (a rewrite that added/removed a line) MUST fail (F4 branch)
        pair_linecount_vpap = os.path.join(td, "pair-linecount-vss-paper.jar")
        _make_jar(pair_linecount_vpap, {"plugin.yml": VSS_PLUGIN_YML + "extra: line\n"})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_linecount_vpap, p)
        check(any("line count differs" in m for m in p),
              f"paper line-count change not caught: {p}")

        # ---- brand.properties (both platforms) ----
        BRAND_LSS_TXT = ("shortName=LSS\ndisplayName=LOD Server Support\n"
                         "clientCommand=lss\nserverCommand=lsslod\n")
        BRAND_VSS_TXT = ("shortName=VSS\ndisplayName=Voxy Server Side\n"
                         "clientCommand=vss\nserverCommand=vsslod\n")
        brand_lss = os.path.join(td, "brand-lss.jar")
        _make_jar(brand_lss, {"lss-brand.properties": BRAND_LSS_TXT})
        brand_vss = os.path.join(td, "brand-vss.jar")
        _make_jar(brand_vss, {"lss-brand.properties": BRAND_VSS_TXT})
        p = []
        check_brand_properties(brand_lss, _BRAND_LSS, p)
        check_brand_properties(brand_vss, _BRAND_VSS, p)
        check(p == [], f"clean brand.properties flagged: {p}")
        # a VSS jar that still carries LSS branding (rewrite no-opped) MUST fail
        p = []
        check_brand_properties(brand_lss, _BRAND_VSS, p)
        check(any("lss-brand.properties" in m for m in p), "VSS brand no-op not caught")
        # a jar with NO brand.properties MUST fail (Brand would fall back to LSS defaults)
        p = []
        check_brand_properties(pair_lss_pap, _BRAND_LSS, p)
        check(any("no lss-brand.properties" in m for m in p), "missing brand.properties not caught")

        # ---- wire identity: nested common jar must be byte-identical across the pair ----
        nested_common = io.BytesIO()
        with zipfile.ZipFile(nested_common, "w") as z:
            z.writestr("dev/vox/lss/common/LSSConstants.class", "lss:handshake_c2s")
        wire_lss_fab = os.path.join(td, "wire-lss-fabric.jar")
        _make_jar(wire_lss_fab, {"META-INF/jars/common-0.7.0.jar": nested_common.getvalue()})
        wire_vss_ok = os.path.join(td, "wire-vss-ok-fabric.jar")
        _make_jar(wire_vss_ok, {"META-INF/jars/common-0.7.0.jar": nested_common.getvalue()})
        p = []
        check_wire_identity_fabric(wire_lss_fab, wire_vss_ok, p)
        check(p == [], f"identical nested common jar flagged: {p}")
        # a common jar that DIFFERS (branding leaked into the wire) MUST fail
        nested_forked = io.BytesIO()
        with zipfile.ZipFile(nested_forked, "w") as z:
            z.writestr("dev/vox/lss/common/LSSConstants.class", "vss:handshake_c2s")
        wire_vss_bad = os.path.join(td, "wire-vss-bad-fabric.jar")
        _make_jar(wire_vss_bad, {"META-INF/jars/common-0.7.0.jar": nested_forked.getvalue()})
        p = []
        check_wire_identity_fabric(wire_lss_fab, wire_vss_bad, p)
        check(any("nested common jar differs" in m for m in p),
              f"wire-identity divergence not caught: {p}")

        # ---- Paper wire identity: the flat-shaded class bytes must match across the pair ----
        wire_lss_pap = os.path.join(td, "wire-lss-paper.jar")
        _make_jar(wire_lss_pap, {"plugin.yml": "name: x\n",
                                 "dev/vox/lss/common/LSSConstants.class": "lss:handshake",
                                 "dev/vox/lss/paper/LSSPaperPlugin.class": "main"})
        wire_vss_pap_ok = os.path.join(td, "wire-vss-ok-paper.jar")
        # Same classes, different plugin.yml (the legitimate rebrand) → wire-identical.
        _make_jar(wire_vss_pap_ok, {"plugin.yml": "name: y\n",
                                    "dev/vox/lss/common/LSSConstants.class": "lss:handshake",
                                    "dev/vox/lss/paper/LSSPaperPlugin.class": "main"})
        p = []
        check_wire_identity_paper(wire_lss_pap, wire_vss_pap_ok, p)
        check(p == [], f"identical paper class bytes flagged: {p}")
        # a changed class byte (branding leaked into behavior) MUST fail
        wire_vss_pap_bad = os.path.join(td, "wire-vss-bad-paper.jar")
        _make_jar(wire_vss_pap_bad, {"plugin.yml": "name: y\n",
                                     "dev/vox/lss/common/LSSConstants.class": "vss:handshake",
                                     "dev/vox/lss/paper/LSSPaperPlugin.class": "main"})
        p = []
        check_wire_identity_paper(wire_lss_pap, wire_vss_pap_bad, p)
        check(any("class bytes differ" in m for m in p),
              f"paper wire-identity divergence not caught: {p}")

        # glob hygiene: a CI-named soak jar must not match any release glob
        p = []
        check_glob_hygiene(p, [os.path.join(td, "lss-paper-soak-0.4.0+26.1.2.jar")])
        check(p == [], f"clean glob hygiene flagged: {p}")
        p = []
        # a soak jar mis-named to look like a release artifact MUST be caught
        check_glob_hygiene(p, [os.path.join(td, "lod-server-support-paper-soaky.jar")])
        check(any("MATCHES release glob" in m for m in p), "mis-named soak jar not caught")
        p = []
        # a soak jar mis-named to look like the VOXY release artifact MUST also be caught
        check_glob_hygiene(p, [os.path.join(td, "voxy-server-side-paper-soaky.jar")])
        check(any("MATCHES release glob" in m for m in p), "mis-named vss soak jar not caught")

        # ---- store-native matrix (plan §3) ----
        sn_fab = os.path.join(td, "store-fab.jar")
        _make_jar(sn_fab, {
            "fabric.mod.json": json.dumps({"version": "1", "jars": STORE_FABRIC_JARS_FIELD}),
            "assets/lss/lang/en_us.json": "{}",
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
            "META-INF/jars/zstd-jni-slim.jar": _nested_zstd(missing_dir="linux/aarch64/"),
        })
        p = []
        check_store_natives_fabric(sn_fab, p)
        check(any("linux/aarch64/" in m for m in p),
              f"fabric jar missing a zstd native not caught: {p}")
        sn_fab_undeclared = os.path.join(td, "store-fab-undeclared.jar")
        _make_jar(sn_fab_undeclared, {
            "fabric.mod.json": json.dumps({"version": "1"}),  # no "jars" field
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
            "assets/lss/lang/en_us.json": "{}",
            "META-INF/jars/zstd-jni-slim.jar": _nested_zstd(),
        })
        p = []
        check_store_natives_fabric(sn_fab_undeclared, p)
        check(any("does not declare" in m for m in p),
              f"undeclared nested store jar not caught: {p}")
        sn_pap = os.path.join(td, "store-pap.jar")
        pap_entries = _store_paper_entries()
        del pap_entries["org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib"]
        _make_jar(sn_pap, pap_entries)
        p = []
        check_store_natives_paper(sn_pap, p)
        check(any("Mac/aarch64" in m for m in p),
              f"paper jar missing a sqlite native not caught: {p}")
        # a regressed strip (an out-of-matrix native shipping) must be caught (F3)
        sn_pap_fat = os.path.join(td, "store-pap-fat.jar")
        fat_entries = _store_paper_entries()
        fat_entries["org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so"] = "elf"
        fat_entries["linux/ppc64le/libzstd-jni-1.5.7-3.so"] = "elf"
        _make_jar(sn_pap_fat, fat_entries)
        p = []
        check_store_natives_paper(sn_pap_fat, p)
        check(any("outside the supported matrix" in m for m in p),
              f"regressed native strip not caught: {p}")

        # ---- third-party notices (review-fixes C6) ----
        tp_ok = os.path.join(td, "tp-ok.jar")
        _make_jar(tp_ok, {
            "THIRD-PARTY-NOTICES": "zstd-jni BSD-2 ... Zstandard BSD-3 ... " + "x" * 100,
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
        })
        p = []
        check_third_party_notices(tp_ok, True, p)
        check(p == [], f"clean notices jar flagged: {p}")
        tp_missing = os.path.join(td, "tp-missing.jar")
        _make_jar(tp_missing, {"some.class": "x"})
        p = []
        check_third_party_notices(tp_missing, False, p)
        check(any("THIRD-PARTY-NOTICES missing" in m for m in p),
              f"missing notices not caught: {p}")
        tp_stripped = os.path.join(td, "tp-stripped-license.jar")
        _make_jar(tp_stripped, {
            "THIRD-PARTY-NOTICES": "x" * 200,
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(with_license=False),
        })
        p = []
        check_third_party_notices(tp_stripped, True, p)
        check(any("lost its in-jar LICENSE" in m for m in p),
              f"stripped nested sqlite license not caught: {p}")

        # ---- discover(): end-to-end wiring over a synthetic build tree ----
        # The leaf checks above prove each check works; these prove discover() actually
        # CALLS them (presence, pair wiring, identity) — a refactor that drops a call
        # would otherwise leave the gate vacuously green.
        droot = os.path.join(td, "tree")
        dfab = os.path.join(droot, "fabric", "build", "libs")
        dpap = os.path.join(droot, "paper", "build", "libs")
        dneo = os.path.join(droot, "neoforge", "build", "libs")
        os.makedirs(dfab)
        os.makedirs(dpap)
        os.makedirs(dneo)
        PY_LSS = ("name: LodServerSupport\nversion: '0.7.0'\n"
                  "main: dev.vox.lss.paper.LSSPaperPlugin\napi-version: '26.2'\n"
                  "folia-supported: true\n"
                  "description: LSS plugin.\n"
                  "author: VoX\n"
                  "website: https://modrinth.com/plugin/lod-server-support\n"
                  "commands:\n  lsslod:\n"
                  "    description: LOD Server Support admin commands\n"
                  "    usage: /lsslod <stats|diag>\n    permission: lss.admin\n"
                  "permissions:\n  lss.admin:\n"
                  "    description: Access to LSS admin commands\n    default: op\n"
                  "  lss.farplayers.hidden:\n    description: hidden\n    default: false\n"
                  "  vss.farplayers.hidden:\n    description: hidden\n    default: false\n")
        # The 2026-08-13 rewrite shape: name/author rebrand; farplayers nodes NOT renamed.
        PY_VSS = (PY_LSS
                  .replace("name: LodServerSupport", "name: VoxyServerSide", 1)
                  .replace("description: LSS plugin.",
                           "description: Render distant Voxy LODs on servers", 1)
                  .replace("author: VoX", "author: Xantha, VoX", 1)
                  .replace("plugin/lod-server-support", "plugin/voxy-server-side", 1)
                  .replace("lsslod", "vsslod")
                  .replace("LOD Server Support admin commands", "Voxy Server Side admin commands")
                  .replace("Access to LSS admin commands", "Access to VSS admin commands")
                  .replace("lss.admin", "vss.admin"))
        BRAND_LSS = ("shortName=LSS\ndisplayName=LOD Server Support\n"
                     "clientCommand=lss\nserverCommand=lsslod\n")
        BRAND_VSS = ("shortName=VSS\ndisplayName=Voxy Server Side\n"
                     "clientCommand=vss\nserverCommand=vsslod\n")
        pap_manifest = "Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n"

        def _write_tree_fabric(name, meta, brand, extra=None, drop=()):
            meta = dict(meta)
            meta.setdefault("jars", STORE_FABRIC_JARS_FIELD)
            entries = {
                "fabric.mod.json": json.dumps(meta),
                "assets/lss/lang/en_us.json": json.dumps(
                    {"lss.config.page": "General",
                     "lss.config.far_players_with_seeu": ("Prefer VSS Far Players" if brand == BRAND_VSS
                                                          else "Prefer LSS Far Players")}),
                "dev/vox/lss/LSSMod.class": "x",
                # A SHARED class (xplat) — the cross-loader presence check's subject.
                "dev/vox/lss/networking/server/RequestProcessingService.class": "x",
                "META-INF/jars/common-0.7.0.jar": _nested_common("0.7.0"),
                "lss-brand.properties": brand,
                "LICENSE_lod-server-support-fabric": "MIT",
                "THIRD-PARTY-NOTICES": "sqlite-jdbc / zstd-jni notices " + "x" * 100,
            }
            entries.update(STORE_FABRIC_ENTRIES)
            entries.update(extra or {})
            for d in drop:
                entries.pop(d, None)
            _make_jar(os.path.join(dfab, name), entries, manifest=fab_manifest)

        def _write_tree_paper(name, yml, brand):
            entries = {
                "plugin.yml": yml,
                "lss-brand.properties": brand,
                "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
                "dev/vox/lss/common/PositionUtil.class": "x",
                "THIRD-PARTY-NOTICES": "sqlite-jdbc / zstd-jni notices " + "x" * 100,
            }
            entries.update(_store_paper_entries())
            _make_jar(os.path.join(dpap, name), entries, manifest=pap_manifest)

        _write_tree_fabric("lod-server-support-fabric.jar",
                           {"id": "lss", "name": "LOD Server Support", "version": "0.7.0"},
                           BRAND_LSS)
        _write_tree_fabric("voxy-server-side-fabric.jar",
                           {"id": "lss", "name": "Voxy Server Side", "version": "0.7.0",
                            "icon": "assets/lss/icon-vss.png"},
                           BRAND_VSS,
                           extra={"assets/lss/icon-vss.png": "PNG"})
        _write_tree_paper("lod-server-support-paper.jar", PY_LSS, BRAND_LSS)
        _write_tree_paper("voxy-server-side-paper.jar", PY_VSS, BRAND_VSS)

        TOML_LSS = ('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n\n[[mods]]\n'
                    'modId="lss"\nversion="0.7.0"\ndisplayName="LOD Server Support"\n'
                    'authors="VoX"\nlogoFile="assets/lss/icon.png"\n'
                    'description=\'\'\'LSS.\'\'\'\n\n'
                    '[[mixins]]\nconfig="lss.neoforge.mixins.json"\n')
        TOML_VSS = (TOML_LSS
                    .replace('displayName="LOD Server Support"', 'displayName="Voxy Server Side"')
                    .replace('authors="VoX"', 'authors="Xantha, VoX"')
                    .replace('logoFile="assets/lss/icon.png"',
                             'logoFile="assets/lss/icon-vss.png"'))

        def _write_tree_neoforge(name, toml, brand, shared_class="x", extra=None, drop=(),
                                  store_entries=None):
            entries = {
                "META-INF/neoforge.mods.toml": toml,
                "lss.neoforge.mixins.json": "{}",
                "lss-sodium-legacy.mixins.json": "{}",
                "assets/lss/lang/en_us.json": json.dumps(
                    {"lss.config.page": "General",
                     "lss.config.far_players_with_seeu": ("Prefer VSS Far Players" if brand == BRAND_VSS
                                                          else "Prefer LSS Far Players")}),
                "META-INF/accesstransformer.cfg": "public net.minecraft.world.level.chunk.PalettedContainer data",
                "META-INF/services/dev.vox.lss.platform.LoaderServices":
                    "dev.vox.lss.platform.NeoForgeLoaderServices",
                "lss-brand.properties": brand,
                "dev/vox/lss/common/PositionUtil.class": "x",
                "dev/vox/lss/networking/server/RequestProcessingService.class": shared_class,
                "dev/vox/lss/neoforge/LSSNeoMod.class": "x",
                "dev/vox/lss/platform/NeoForgeLoaderServices.class": "x",
                "dev/vox/lss/platform/NeoForgeClientLoaderServices.class": "x",
                "dev/vox/lss/config/LSSConfigMenu.class": "x",
                "dev/vox/lss/common/store/SqliteLodStore.class":
                    "ref org/sqlite/SQLiteDataSource ok",
                "assets/lss/icon.png": "PNG",
                "THIRD-PARTY-NOTICES": "sqlite-jdbc / zstd-jni notices " + "x" * 100,
            }
            entries.update(_store_neoforge_entries() if store_entries is None
                           else store_entries)
            if brand == BRAND_VSS:
                entries["assets/lss/icon-vss.png"] = "PNG"
            entries.update(extra or {})
            for d in drop:
                entries.pop(d, None)
            _make_jar(os.path.join(dneo, name), entries)

        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS)
        p = []
        fab_d, pap_d, vfab_d, vpap_d, neo_d, vneo_d, _ = discover(p, root=droot)
        check(p == [] and len(fab_d) == len(pap_d) == len(vfab_d) == len(vpap_d)
              == len(neo_d) == len(vneo_d) == 1,
              f"clean synthetic tree flagged by discover: {p}")

        # a neoforge jar whose entrypoint/seam classes were shaded OUT must fail
        # (round-3 review NIT-3: the gametest smoke runs off classes dirs, never the
        # jar, so a careless shadowJar exclude shipped an entrypoint-less jar green)
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             drop=("dev/vox/lss/neoforge/LSSNeoMod.class",))
        p = []
        check_neoforge_jar(os.path.join(dneo, "lod-server-support-neoforge.jar"), p)
        check(any("LSSNeoMod" in m and "excluded from the shadow jar" in m for m in p),
              f"entrypoint-less neoforge jar not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # a logoFile pointing at a missing icon entry must red (the mods-screen
        # icon parity fix — fabric/neoforge lists must match)
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             drop=("assets/lss/icon.png",))
        p = []
        check_neoforge_jar(os.path.join(dneo, "lod-server-support-neoforge.jar"), p)
        check(any("logoFile points at" in m for m in p),
              f"logoFile referencing a missing icon not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # sodium-options-page-generations-plan.md §4: the legacy Sodium options hook's
        # config and the lang file are release-gated on NeoForge (a lost
        # processResources copy or a dropped [[mixins]] resource ships a page-less or
        # raw-key jar with every unit gate green)
        for dropped in ("lss-sodium-legacy.mixins.json", "assets/lss/lang/en_us.json"):
            _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                                 drop=(dropped,))
            p = []
            check_neoforge_jar(os.path.join(dneo, "lod-server-support-neoforge.jar"), p)
            check(any(f"missing {dropped}" in m for m in p),
                  f"neoforge jar without {dropped} not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)
        # the fabric jar's lang file is release-gated too (the page has shipped there for
        # two releases; a lost resource copy must red here, not in a user's screen)
        _write_tree_fabric("lod-server-support-fabric.jar",
                           {"id": "lss", "name": "LOD Server Support", "version": "0.7.0"},
                           BRAND_LSS, drop=("assets/lss/lang/en_us.json",))
        p = []
        check_fabric_jar(os.path.join(dfab, "lod-server-support-fabric.jar"), p)
        check(any("missing assets/lss/lang/en_us.json" in m for m in p),
              f"fabric jar without the lang file not caught: {p}")
        _write_tree_fabric("lod-server-support-fabric.jar",
                           {"id": "lss", "name": "LOD Server Support", "version": "0.7.0"},
                           BRAND_LSS)
        # a lang rebrand that DROPS a key (a broken round trip) must red on the key-set pin
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS,
                             extra={"assets/lss/lang/en_us.json": json.dumps(
                                 {"lss.config.page": "General"})})
        p = []
        check_vss_pair_neoforge(os.path.join(dneo, "lod-server-support-neoforge.jar"),
                                os.path.join(dneo, "voxy-server-side-neoforge.jar"), p)
        check(any("key set differs" in m for m in p),
              f"lang key-set drift on the neoforge VSS pair not caught: {p}")
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS)
        # the VSS neoforge lang rewrite must actually rebrand (the fabric pair's V-M2 pin)
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS,
                             extra={"assets/lss/lang/en_us.json": json.dumps(
                                 {"lss.config.far_players_with_seeu": "Prefer LSS Far Players"})})
        p = []
        check_vss_pair_neoforge(os.path.join(dneo, "lod-server-support-neoforge.jar"),
                                os.path.join(dneo, "voxy-server-side-neoforge.jar"), p)
        check(any("still carries LSS branding" in m for m in p),
              f"un-rebranded neoforge VSS lang not caught: {p}")
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS)

        # jarjar-shape negatives (neoforge-jarjar-sqlite-plan.md §4): metadata
        # pointing at a missing nested jar; flat org/sqlite leaking back beside the
        # nested shape; a foreign identifier group; a native missing INSIDE the
        # nested jar. Each must red through check_store_natives_neoforge.
        neo_jar = os.path.join(dneo, "lod-server-support-neoforge.jar")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             store_entries=_store_neoforge_entries(drop_nested_jar=True))
        p = []
        check_store_natives_neoforge(neo_jar, p)
        check(any("nested sqlite jar is missing" in m for m in p),
              f"missing nested sqlite jar not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             extra={"org/sqlite/JDBC.class": "x"})
        p = []
        check_store_natives_neoforge(neo_jar, p)
        check(any("flat org/sqlite" in m for m in p),
              f"flat sqlite leak beside the nested jar not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             store_entries=_store_neoforge_entries(group="org.example"))
        p = []
        check_store_natives_neoforge(neo_jar, p)
        check(any("exactly one org.xerial:sqlite-jdbc" in m for m in p),
              f"foreign jarjar identifier not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             store_entries=_store_neoforge_entries(
                                 drop_native=SQLITE_NATIVES[0]))
        p = []
        check_store_natives_neoforge(neo_jar, p)
        check(any("missing sqlite native" in m for m in p),
              f"missing native inside the nested jar not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # Round-2 hardening negatives (the 3-Opus execution review): every remaining
        # branch of check_store_natives_neoforge pinned, plus the multi-entry positive.
        def _neo_case(expect, why, **knobs):
            _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                                 store_entries=_store_neoforge_entries(**knobs))
            probs = []
            check_store_natives_neoforge(neo_jar, probs)
            check(any(expect in m for m in probs), f"{why}: {probs}")
        _neo_case("missing META-INF/jarjar/metadata.json",
                  "missing metadata not caught", drop_metadata=True)
        _neo_case("does not parse", "unparseable metadata not caught", raw_meta="{nope")
        _neo_case("has no 'jars' list", "jars-not-a-list not caught",
                  raw_meta='{"jars": "x"}')
        _neo_case("disagrees with the nested jar filename",
                  "version/filename disagreement not caught", bad_version=True)
        _neo_case("lost META-INF/maven/org.xerial/sqlite-jdbc/LICENSE",
                  "missing nested license not caught", drop_license=True)
        _neo_case("undeclared in jarjar metadata",
                  "undeclared nested jar not caught", undeclared_extra=True)
        _neo_case("looks TRIMMED", "trimmed nested jar not caught", stock=False)
        _neo_case("lost its module-info", "module-info loss not caught", stock=False)
        wrong_range = json.dumps({"jars": [{
            "identifier": {"group": "org.xerial", "artifact": "sqlite-jdbc"},
            "version": {"range": "[3.49.1.0,)", "artifactVersion": "3.49.1.0"},
            "path": "META-INF/jarjar/sqlite-jdbc-3.49.1.0.jar",
            "isObfuscated": False}]})
        _neo_case("jarjar range", "wrong range not caught (the M4 decision unpinned)",
                  raw_meta=wrong_range)
        bad_path = json.dumps({"jars": [{
            "identifier": {"group": "org.xerial", "artifact": "sqlite-jdbc"},
            "version": {"range": "[3.49.1.0,4.0.0.0)", "artifactVersion": "3.49.1.0"},
            "path": "libs/sqlite-jdbc-3.49.1.0.jar", "isObfuscated": False}]})
        _neo_case("sits outside META-INF/jarjar/",
                  "escaping path not caught", raw_meta=bad_path)
        # relocated store class: the flat class no longer names sqlite unrelocated
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             extra={"dev/vox/lss/common/store/SqliteLodStore.class":
                                    "relocated refs only"})
        p = []
        check_store_natives_neoforge(neo_jar, p)
        check(any("no longer references" in m for m in p),
              f"relocated store-class references not caught: {p}")
        # multi-entry POSITIVE: a second declared+present library must not red
        second = io.BytesIO()
        with zipfile.ZipFile(second, "w") as z:
            z.writestr("y.txt", "y")
        multi_entries = _store_neoforge_entries()
        multi = json.loads(multi_entries["META-INF/jarjar/metadata.json"])
        multi["jars"].append({"identifier": {"group": "org.example", "artifact": "other"},
                              "version": {"range": "[1.0,)", "artifactVersion": "1.0"},
                              "path": "META-INF/jarjar/other-1.0.jar",
                              "isObfuscated": False})
        multi_entries["META-INF/jarjar/metadata.json"] = json.dumps(multi)
        multi_entries["META-INF/jarjar/other-1.0.jar"] = second.getvalue()
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             store_entries=multi_entries)
        p = []
        check_store_natives_neoforge(neo_jar, p)
        check(p == [], f"multi-entry metadata with one sqlite entry must pass: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # a missing vss family must fail the gate (silently unwired repackage task)
        os.remove(os.path.join(dfab, "voxy-server-side-fabric.jar"))
        p = []
        discover(p, root=droot)
        check(any("no voxy-server-side-fabric jar" in m for m in p),
              f"missing vss fabric family not caught by discover: {p}")

        # a forked vss id must be caught THROUGH discover (identity wiring intact)
        _write_tree_fabric("voxy-server-side-fabric.jar",
                           {"id": "vss", "name": "Voxy Server Side", "version": "0.7.0",
                            "icon": "assets/lss/icon-vss.png"},
                           BRAND_VSS,
                           extra={"assets/lss/icon-vss.png": "PNG"})
        p = []
        discover(p, root=droot)
        check(any("must stay 'lss'" in m for m in p),
              f"forked vss id not caught through discover: {p}")
        # restore the clean fabric vss fixture for the neoforge cases below
        _write_tree_fabric("voxy-server-side-fabric.jar",
                           {"id": "lss", "name": "Voxy Server Side", "version": "0.7.0",
                            "icon": "assets/lss/icon-vss.png"},
                           BRAND_VSS,
                           extra={"assets/lss/icon-vss.png": "PNG"})

        # the missing-family polarity follows SHIP_NEOFORGE (v0.11.0 scope): on a
        # shipping line an absent family is a release blocker (N-4); on a
        # non-shipping line it must be TOLERATED (release.yml never builds it there)
        os.remove(os.path.join(dneo, "lod-server-support-neoforge.jar"))
        p = []
        discover(p, root=droot)
        if SHIP_NEOFORGE:
            check(any("no lod-server-support-neoforge jar" in m for m in p),
                  f"missing neoforge family not caught: {p}")
        else:
            check(not any("no lod-server-support-neoforge jar" in m for m in p),
                  f"non-shipping line must tolerate a missing neoforge family: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # a VSS neoforge jar with drifted class bytes must fail wire identity
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS,
                             shared_class="DRIFTED")
        p = []
        discover(p, root=droot)
        check(any("byte-copy rebrand" in m for m in p),
              f"vss neoforge class drift not caught: {p}")
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS)

        # a leaked dev-only class must fail the forbidden scan
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             extra={"dev/vox/lss/benchmark/BenchmarkHook.class": "x"})
        p = []
        discover(p, root=droot)
        check(any("benchmark" in m and "neoforge" in m for m in p),
              f"neoforge forbidden class not caught: {p}")

        # a leaked compile-only Sodium stub must fail the forbidden scan (net/caffeinemc)
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             extra={"net/caffeinemc/mods/sodium/api/config/ConfigEntryPoint.class": "x"})
        p = []
        discover(p, root=droot)
        check(any("caffeinemc" in m and "neoforge" in m for m in p),
              f"neoforge sodium-stub leak not caught: {p}")

        # a dropped mixin-config declaration must be caught (accessors silently dead)
        _write_tree_neoforge("lod-server-support-neoforge.jar",
                             TOML_LSS.replace('[[mixins]]\nconfig="lss.neoforge.mixins.json"\n', ""),
                             BRAND_LSS)
        p = []
        discover(p, root=droot)
        check(any("lost the mixin config declaration" in m for m in p),
              f"missing mixin declaration not caught: {p}")

        # cross-loader drift: the fabric jar ships a shared class the neoforge jar lacks
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             drop=("dev/vox/lss/networking/server/RequestProcessingService.class",))
        p = []
        discover(p, root=droot)
        check(any("twin drift dropped shared surface" in m for m in p),
              f"cross-loader class drop not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # cross-loader drift, NESTED flavor (N-4 review): fabric ships common/ inside
        # META-INF/jars, so a neoforge shade slimming a common class must still red
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS,
                             drop=("dev/vox/lss/common/PositionUtil.class",))
        p = []
        discover(p, root=droot)
        check(any("twin drift dropped shared surface" in m for m in p),
              f"nested-common class drop not caught: {p}")
        _write_tree_neoforge("lod-server-support-neoforge.jar", TOML_LSS, BRAND_LSS)

        # the VSS TOML pair check (N-4 review): a non-branding line drift must red
        _write_tree_neoforge("voxy-server-side-neoforge.jar",
                             TOML_VSS.replace('loaderVersion="[4,)"', 'loaderVersion="[9,)"'),
                             BRAND_VSS)
        p = []
        discover(p, root=droot)
        check(any("differs outside the branding keys" in m for m in p),
              f"vss neoforge non-branding TOML drift not caught: {p}")

        # ... and a corrupted rewrite that changes the line count (the reflowed-construct
        # replaceFirst failure mode: orphaned continuation + dangling triple-quote)
        _write_tree_neoforge("voxy-server-side-neoforge.jar",
                             TOML_VSS + "orphaned continuation text'''\n", BRAND_VSS)
        p = []
        discover(p, root=droot)
        check(any("line count differs" in m for m in p),
              f"vss neoforge TOML line-count corruption not caught: {p}")
        _write_tree_neoforge("voxy-server-side-neoforge.jar", TOML_VSS, BRAND_VSS)

    print(f"release_check selftest OK: {n} cases")
    return 0


# ----------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(description="Gate release jars + publish metadata before shipping.")
    ap.add_argument("--selftest", action="store_true", help="run synthetic-fixture checks and exit")
    ap.add_argument("--version", metavar="X.Y.Z",
                    help="require and check exactly the release jars for this mod_version "
                         "(ignores stale artifacts from earlier builds)")
    args = ap.parse_args(argv)
    if args.selftest:
        return _selftest()

    problems = []
    fab, pap, vfab, vpap, neo, vneo, soak = discover(problems, expected_version=args.version)
    print(f"release_check: lss(fabric={len(fab)} paper={len(pap)} neoforge={len(neo)}) "
          f"vss(fabric={len(vfab)} paper={len(vpap)} neoforge={len(vneo)}) soak={len(soak)}")
    if problems:
        print(f"FAIL: {len(problems)} release problem(s):")
        for m in problems:
            print(f"  - {m}")
        return 1
    print("OK: release artifacts clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
