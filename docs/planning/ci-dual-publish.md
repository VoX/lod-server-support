# CI Dual-Publish: LOD Server Support + Voxy Server Side

**Status:** IMPLEMENTED on `feat/want-set-requests`, shipping with **v0.7.0** (the original
plan proposed landing after v0.7.0; the maintainer chose to debut dual-publish on this
release). Written 2026-07-17. The Gradle repackage tasks, the extended `release_check.py`
gate + identity guardrail, and the four-jar build/release workflows are all in the tree and
validated (four jars build at 0.7.0, `release_check --version 0.7.0` clean, the voxy jars are
CRC-proven branded byte-copies of the LSS jars). The one live prerequisite before the tag —
`MODRINTH_TOKEN` membership on the `voxy-server-side` project (id `84zcagOb`) with
version-upload permission — was **RESOLVED at v0.7.1** (2026-07-22): the dual-publish ran
end-to-end in CI, both Modrinth projects received the release, no manual upload needed.

## Goal

Every CI build produces **four** release jars instead of two — the existing
`lod-server-support-*` pair plus a genuinely-branded `voxy-server-side-*` pair — and real
releases publish the LSS jars to the existing Modrinth project (`lKiXKLvv`) and the
voxy-branded jars to the [`voxy-server-side`](https://modrinth.com/plugin/voxy-server-side)
project, now that we have distribution access to it.

The two jar families are the **same mod** — identical mod id, plugin name, config paths, and
behavior, so a user can swap one for the other with no config loss. They differ only in
filename and display branding (name / description / icon). The branding lives on the jar's
mod-menu metadata and the Modrinth project page; the *identity* is deliberately shared.

## Current machinery (what this changes)

- **Jar names** — `gradle.properties` sets `archives_base_name=lod-server-support-fabric`;
  the two `build.gradle` files derive the Paper name and append `+<mc_version>` +
  `mod_version` under `CI=true` (`lod-server-support-<platform>-<mod_version>+<mc_version>.jar`),
  keeping stable unversioned names locally. `mod_version` is fed from the tag by `release.yml`
  (`-Pmod_version=${GITHUB_REF_NAME#v}`).
- **`fabric.mod.json`** — already carries `"id": "lss"`, `"name": "LOD Server Support"`, a
  `description`, and `"icon": "assets/lss/icon.png"` (the icon file exists in resources).
  `${version}` is templated by `processResources`.
- **`plugin.yml`** — `name: LodServerSupport` (this IS the plugin identity / config-folder
  name on Paper), a description, `${version}` templated. No icon concept.
- **`release.yml`** — builds Fabric + Paper with the tag version, runs
  `release_check.py --version`, attaches the two jars to the GitHub release, then two
  `mc-publish@v3.3` steps publish to `lKiXKLvv` (Fabric → `fabric`; Paper → `paper`,`purpur`;
  both `game-versions: 26.2`; changelog from the tag annotation).
- **`build.yml`** — builds both platforms on every push/PR, runs `release_check.py` (no
  version pin) as the safety gate, uploads jars as artifacts.
- **`release_check.py`** — hardcodes
  `RELEASE_GLOBS = ("lod-server-support-fabric-*.jar", "lod-server-support-paper-*.jar")` and
  verifies the jars carry no dev/soak/benchmark packages (incl. nested Jar-in-Jar), match the
  tag version, and have clean globs. The soak jar (`lss-paper-soak-*.jar`) is deliberately
  outside the release globs.

## Resolved design decisions

1. **Branded voxy variant, shared identity.** Not a plain rename — the voxy jars are
   rebranded, but keep identity fields identical for drop-in interchangeability:

   | Field | LSS jar | Voxy jar |
   |---|---|---|
   | Fabric mod `id` / Paper plugin `name` | `lss` / `LodServerSupport` | **unchanged** (identity + config paths) |
   | Fabric `name` (mod-menu title) | LOD Server Support | **Voxy Server Side** |
   | `description` (Fabric + Paper) | current | Voxy-focused wording |
   | Fabric `icon` | `assets/lss/icon.png` | **`assets/lss/icon-vss.png`** |
   | `contact` / homepage | GitHub | + link to the voxy-server-side Modrinth page |

2. ~~**v0.7.0 ships LSS-only, as currently staged.** Dual-publish debuts on the **next**
   release, so this CI change lands and gets exercised on a non-critical release first.~~
   **Superseded:** the maintainer chose to debut dual-publish **on v0.7.0**. The change is
   additive (the LSS jars are untouched byte-for-byte) and fully gated by `release_check.py`,
   so the risk of shipping it on this release is contained; the one thing to confirm first is
   the Modrinth membership prerequisite (§Prerequisites).

3. **GitHub release attaches all four jars** — one download source of truth. (Adjust if you'd
   rather keep the GitHub release LSS-named and dual-publish only on Modrinth.)

## Cross-compatibility (verified 2026-07-17)

**LSS and VSS jars are fully interchangeable on the wire — all four client↔server
combinations work, and a server cannot even tell which jar the client runs.** This is
structural, not incidental, and was verified against the code:

- **The handshake carries only `(int protocolVersion, int capabilities)`** — two VarInts, no
  brand/name/version string (`HandshakeC2SPayload`). The server's `HandshakeGate` keys purely
  on the protocol version int (18) and the capability bitmask.
- **Protocol version, channel ids (`lss:*`), and the capability bit are compiled constants**
  in `LSSConstants`, not derived from mod metadata.
- **The VSS jar is a repackaged copy of the LSS jar** — identical `.class` files, mixins,
  `entrypoints`, and shared resources; only `fabric.mod.json` (name/description/icon/contact),
  `plugin.yml` (description), the bundled icon, and the filename differ. So every wire behavior
  (handshake, channels, serialization, capabilities) is byte-identical by construction.
- **Config paths are hardcoded, brand-independent** — `config/lss/…`,
  `lss-server-config.json`, `lss-client-config.json`, Paper's `plugins/LodServerSupport/…`.
  Mod id stays `lss`; plugin name stays `LodServerSupport`. So swapping an LSS jar for a VSS
  jar on the same install keeps all config seamlessly.

Result: LSS↔LSS, LSS↔VSS, VSS↔LSS, VSS↔VSS all negotiate the identical protocol-18 session.

**Deliberate consequences (features, not bugs):**
- You **cannot install both jars in one instance** — Fabric rejects the duplicate mod id
  `lss`; Paper rejects two `LodServerSupport` plugins. This prevents accidental double-install;
  users pick one. Worth a note on the Modrinth pages.
- **Config is shared on swap** — rebranding LSS→VSS (or back) on an existing install is a
  drop-in file swap with zero config loss.

**Invariants the branded build MUST preserve (the compatibility contract):** the metadata
overlay may change ONLY `name` / `description` / `icon` / `contact` (Fabric) and the top-level
`description` / `website` lines (Paper). It must leave `id` (`lss`), `entrypoints`, `mixins`,
`depends`, and (Paper) `name: LodServerSupport` untouched — those are what keep the two jars
wire-identical and config-interchangeable. In-jar display surfaces derive branding from the
descriptor at runtime (the Sodium config screen reads the metadata name/icon), so the overlay
is the single branding source. The `release_check.py`
identity guardrail (§2) pins `id`/plugin-`name`; the overlay's field scope pins the rest.
Choosing **repackage-the-built-jar over a parameterized recompile** is itself a compatibility
guarantee: identical compiled bytes can't let a brand flag leak into a protocol constant.

## The icon

- **Bundle it in the Fabric voxy jar.** Download the VSS icon
  `https://cdn.modrinth.com/data/84zcagOb/8f13eacb45ff56be05d77190237cd7d159cb136f.png`
  and commit it as `fabric/src/main/resources/assets/lss/icon-vss.png`; the voxy variant's
  `fabric.mod.json` points `icon` at it → the Fabric mod menu shows the VSS logo.
- **The Modrinth project-page icon is already set** by XANTHA at the project level (that CDN
  URL *is* the `voxy-server-side` project icon). `mc-publish` uploads versions, not project
  icons — nothing per-release to do there. Paper plugins have no embedded icon, so the icon is
  Fabric-mod-menu + Modrinth-page only.

## Concrete changes

### 1. Branded-jar production (Gradle)

Produce the voxy jar by **post-processing the built release jar**, not a second compile/remap:

- **Fabric** — a `Jar` task takes `remapJar`'s output, replaces `fabric.mod.json` with the
  voxy-branded version and swaps in `icon-vss.png`, writes
  `voxy-server-side-fabric-<ver>.jar` (same CI-vs-local version logic as the LSS jar).
- **Paper** — a `Jar` task takes the release `shadowJar` output, replaces `plugin.yml`
  (description only; `name` stays `LodServerSupport`), writes
  `voxy-server-side-paper-<ver>.jar`.
- **Single-source the shared fields.** The voxy `fabric.mod.json` / `plugin.yml` are small
  **overlays** (only the branded fields differ); everything else — `version`, `depends`,
  `entrypoints`, `mixins`, `api-version`, commands — is generated from the same source as the
  LSS metadata so the two can't drift. Simplest implementation: generate both metadata files
  from one template with a `brand` parameter, or apply a targeted JSON/YAML edit to the built
  jar's descriptor at package time.
- **Single build+remap** — the voxy jars are repackaged copies, so no double compilation.

### 2. `release_check.py` — gate all four jars (the most important change)

The voxy jars ship to real users, so they get the **identical** safety gate:

- Extend `RELEASE_GLOBS` to include `voxy-server-side-fabric-*.jar` /
  `voxy-server-side-paper-*.jar`.
- Extend `discover()` / `_require_version()` / `check_glob_hygiene()` to find and version-pin
  the voxy jars alongside the LSS jars, with the same no-dev-package / nested-jar / namespace
  checks.
- **New identity guardrail:** assert the voxy Fabric jar's `fabric.mod.json` still has
  `"id": "lss"` and the voxy Paper jar's `plugin.yml` still has `name: LodServerSupport` — so
  a future branding edit can never silently fork the identity and break config
  interchangeability.
- Extend `--selftest` fixtures to prove a voxy jar with a stray dev package / wrong version /
  forked id **fails** the gate.

### 3. `build.yml`

- The extra jars appear automatically from the Gradle change.
- Add them to the artifact uploads.
- The existing `release_check.py` step now validates all four (once the script is extended) —
  no workflow logic change beyond the artifact globs.

### 4. `release.yml`

- Build produces all four (same build commands; the voxy jars are `finalizedBy` outputs).
- Add the voxy jars to the GitHub-release `files` globs.
- Add **two new `mc-publish` steps** targeting `voxy-server-side` (Fabric + Paper), mirroring
  the existing LSS steps' loaders / game-versions / changelog:
  - Fabric → `loaders: fabric`
  - Paper → `loaders: paper, purpur` (add `folia` when Folia 26.2 ships, same as the LSS step)
  - `modrinth-id: voxy-server-side` (slug; confirm the canonical project id once membership is
    live), `modrinth-token: ${{ secrets.MODRINTH_TOKEN }}` (same secret — see prerequisite 1),
    `changelog: ${{ steps.release_notes.outputs.notes }}`.

## Prerequisites / blockers

1. **Modrinth version-publish access to `voxy-server-side`.** The existing `MODRINTH_TOKEN`
   belongs to your account; for it to publish to XANTHA's project, **your account must be a
   member of `voxy-server-side` with version-upload permission** — exactly the access the
   XANTHA arrangement grants. No new secret once membership exists; until then the voxy
   `mc-publish` steps 403.
2. **Project identifier** — resolved: the canonical Modrinth project id is **`84zcagOb`**
   (it is the `data/<id>/` segment of the project-icon CDN URL), and that is what the two voxy
   `mc-publish` steps in `release.yml` target — more robust than the `voxy-server-side` slug.
3. **Reupload-permission statement** — already live on `main` (PR #40); Modrinth's policy box
   is satisfied.

## Sequencing & risk

- ~~**Land after v0.7.0 tags.** First dual-publish = the next release.~~ **Superseded — v0.7.0
  IS the first dual-publish** (see the Status header and resolved decision #2).
- **Risk is low and contained:** the Gradle change only *adds* repackaged branded copies (LSS
  jars untouched); `release_check.py`'s extension is the real safety net for the new
  user-facing artifacts.
- **Do NOT smoke-test with a throwaway pre-release tag** — `release.yml` triggers on every
  `v*` tag, so an rc tag runs the FULL pipeline including the two irreversible LSS publishes
  to the real `lKiXKLvv` project. The safe pre-tag verification is an **authenticated** API
  check with the real token (proves the project id AND membership in one call):
  `curl -H "Authorization: <MODRINTH_TOKEN>" https://api.modrinth.com/v2/project/84zcagOb`
  must return 200. Unauthenticated checks are useless here: the project currently 404s
  publicly (delisted/draft), which an authenticated member call sees through.
- **Never `gh run rerun` a partially-published release.** A re-run rebuilds byte-different
  jars, silently replaces the GitHub release assets, and creates DUPLICATE Modrinth versions
  (nothing server-side rejects a second `v0.7.0+fabric+mc26.2`). Red before the gh-release
  step = nothing published, full re-run is safe. Red after any publish step = recover by
  hand-uploading the GitHub-attached jars to whatever channel missed (the deliberate
  LSS-before-voxy step order means the usual partial state is "LSS live, voxy missing",
  fixed by uploading the two attached voxy jars to `84zcagOb` manually).
- **Interchangeability is the invariant to protect** — hence the `release_check.py` identity
  guardrail. If a future maintainer wants the voxy jar to be a *separate* mod (own id / config),
  that is a different, larger decision (breaks swap-compatibility) and should be made
  deliberately, not by drift.

## Open items for your call

- Decision #3 above (GitHub release attaches all four vs. LSS-only + Modrinth-dual).
- Exact voxy `description` / `contact` wording ("and everything" — how much to credit XANTHA
  in the voxy jar's `authors` / `contact`).
- ~~Whether v0.7.0 should retroactively dual-publish (vs. starting clean at the next release)~~
  — resolved: v0.7.0 dual-publishes. The remaining dependency is the `voxy-server-side`
  membership being live before the tag (see Prerequisites).
