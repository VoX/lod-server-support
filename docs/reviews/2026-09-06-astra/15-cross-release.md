# Cross-line build, release, branding and toolchain review

Lines: 26.2 (`2b2a0df7`), 26.1 (`8e900e69`), 1.21.11 (`da726358`), 1.21.10 (`d08faa91`), 1.21.1 (`1b544494`). Exact baselines in `scope.json`. Read-only review; no builds, servers, publishing, repository changes or children. Existing built jars inspected as snapshots, not claimed to be independently rebuilt from these commits.

## Conclusion

No new P1/P2 product defect or release-blocking metadata mismatch established. One P3 validation/reproducibility gap and stale operational prose are recorded separately below. The differences in shipping scope, mapping namespace, Java targets, Folia declarations and Sodium generation support match the lines’ documented decisions and contract tests.

## REL-1 — P3 validation gap: 1.21.11’s two C2ME validation routes select different releases

**Affected line/files:** `1.21.11/test-server.sh:49` selects Modrinth ID `MfQIu1Y0`, named `c2me-fabric-mc1.21.11-0.4.0-alpha.0.18.jar`; `1.21.11/fabric/build.gradle:113-114` selects `879vA5z6`, documented there as `0.4.0-alpha.0.26+1.21.11`.

**Trigger/consequence:** a reviewer reproduces C2ME behavior with `test-server.sh run-fabric` while another uses the Gradle `benchmark.c2me=true` arm. They run different optimization builds despite both being the line’s normal C2ME validation path. A green result from one does not establish the other build’s callback/reflection behavior. This is not evidence that either build is incompatible and does not justify automatically upgrading either pin.

**Verification:** direct source comparison across all five lines. The other lines’ C2ME selections are aligned by version/ID in the checked declarations (26.2 derives its Gradle ID and checks URL consistency; 26.1 and 1.21.1 match literal IDs; 1.21.10 uses the same named alpha.11 release through differing coordinate forms). No live downloads or runtime trials were performed, and no dated explanation of the 1.21.11 split was established in the inspected guidance.

**Proposed fix/test:** choose and record one deliberate shared validation pin, or explicitly document the two-version matrix and exercise both. Add a source/metadata consistency check if the desired policy is one pin; derive the script and Gradle coordinate from one line-data entry as main already does. Keep any actual compatibility claim tied to the artifact tested.

## Read-only artifact evidence

`15-artifact-inspection.json` records the fifteen existing LSS jars, their SHA-256 values, manifests, metadata, LSS class-version counts and nested-jar identities. The corresponding fifteen VSS jars were also inspected.

| Line | MC compile target | Platform Java / class major | Fabric release namespace | NeoForge ships | Paper/Folia metadata |
|---|---|---|---|---|---|
| 26.2 | 26.2 | 25 / 69 | official | yes | Folia true |
| 26.1 | 26.1.2 | 25 / 69 | official | yes | Folia true |
| 1.21.11 | 1.21.11 | 21 / 65 | intermediary | no | Folia true |
| 1.21.10 | 1.21.10 | 21 / 65 | intermediary | no | Folia false |
| 1.21.1 | 1.21.1 | 21 / 65 | intermediary | yes | Folia absent |

- All five Fabric release manifests and access-widener headers match the expected namespaces. Every Fabric main/trace/legacy-Sodium mixin JSON has the line’s correct JAVA_25 or JAVA_21 compatibility level. Compiled platform classes carry the expected major. Shared common classes deliberately remain Java 21, including when shaded into Java-25 platform jars.
- All fifteen LSS/VSS pairs have equal complete `.class` entry sets and byte-identical class contents. Every nested jar is also byte-identical within its LSS/VSS pair. This verifies the repackaging identity claim for these artifact snapshots; wire behavior across different MC lines belongs to the wire lens.
- Every NeoForge jar contains exactly the expected SQLite and Zstd nested artifacts under `META-INF/jarjar`, with metadata paths and versions present. Neither library’s classes are flat-shaded into the outer jar. Nested SHA-256 values match the locally cached Maven artifacts for `sqlite-jdbc:3.49.1.0` and `zstd-jni:1.5.7-3`, establishing stock-byte identity beyond the release checker’s stockness proxies. SQLite has 24 native files and Zstd 18 in these stock jars.
- Every Fabric jar nests its common jar and the slim SQLite/Zstd libraries; the latter retain 8 SQLite and 6 Zstd native files. Paper carries the intended flat-shaded runtime libraries. Native stripping differs from stock NeoForge nesting by design.
- Built Fabric descriptors retain mod ID `lss`, version `0.14.0`, correct MC dependency ranges/exact values, and line-specific Fabric API floors. Paper descriptors have expanded version/API tokens and the intended Folia values. No wrong-line namespace or obvious unexpanded release placeholder was found.

## Source/contract coverage

Reviewed each CLAUDE banner and the relevant per-version-surfaces rows, line.env and gradle.properties on all five lines; mechanical diffs of root/common/platform Gradle files, build/release workflows, Fabric metadata, NeoForge TOML, Paper plugin.yml and test-server.sh. Read the shared native packaging and VSS rewrite paths and release-check checks for native layout, module metadata, mapping namespaces, shipping-family discovery and VSS identity. Inspected the toolchain and release workflow contract tests and the pinned Sodium/per-line shape guidance. Consulted `cross-line-inventory.json` to avoid treating planned version substitutions as missing ports.

- 26.x uses the direct official-mapped Fabric Loom toolchain; 1.21.x uses loom-remap plus official Mojang development mappings and `remapJar` before VSS repacking.
- Java setup/build flags agree: CI selects 25 on 26.x, 21 on 1.21.x; NeoForge toolchains and Fabric/Paper releases agree. Common intentionally targets 21 everywhere.
- Release tags are line-suffix guarded before build/publish. Support releases cannot take the latest badge. Dispatch remains a dry rehearsal and cannot publish. Actual publishing is push/tag gated.
- Current NeoForge shipping flags are true on 26.2, 26.1 and 1.21.1; false on 1.21.11/1.21.10. The release checker’s derived/main and hand-mirrored/support values agree. Nonshipping NeoForge still builds/tests in normal CI; this is maintained-source scope, not a promise to publish it.
- 26.1 Fabric intentionally advertises the 26.1/26.1.1/26.1.2 range while compiling on 26.1.2. Paper and NeoForge publish for 26.1.2. Exact 1.21.x MC rows match their built metadata.
- Folia is correctly excluded from 1.21.10 and 1.21.1 publication lists. Their false-versus-absent plugin flag distinction is intentional and contract-pinned, not an inconsistency requiring normalization.
- 1.21.10 deliberately lacks the modern Sodium walker/entrypoint and NeoForge config-API stubs, retaining the legacy Sodium page and ModMenu entrypoint. Other lines retain modern wiring; 1.21.1 additionally supports legacy 0.6. Modern NeoForge stubs are excluded from packaged jars by the existing build/check contract.
- 1.21.1’s removed Tier 3 job, build exclusion differences, gametest namespace filtering and common/additional-runtime-classpath dev wiring are documented line adaptations. Main’s later generic Tier-3 switch/poison-pill mechanism was not required to be backported verbatim.
- `test-server.sh` MC, Java and Fabric API selections match the respective line targets. NeoForge version derives from gradle.properties on each line. No repository-owned Prism profile installer/launcher was found; checked scripts only refer to external `lss-multi-test` Prism profiles. Their actual installed contents were not inspected.

## Branding/adoption decisions explicitly ruled out as bugs

- VSS’s mod ID, package names, channels and class bytes stay `lss`/unchanged; only allowed display/local identity surfaces are rewritten. This is deliberate compatibility, not an incomplete rebrand.
- Config filenames adopt the other brand’s existing file within the resolved directory, and successful loads save to that same filename. World LOD stores prefer the active brand’s directory and adopt the other brand’s existing directory when the preferred one is absent. Client cache identity itself was reviewed in the client lens.
- **Paper’s VSS plugin-name change deliberately creates a different plugin data folder.** See `26.2/paper/build.gradle:174-185` and `:210-215`, plus `scripts/release_check.py:768-772`: `LodServerSupport` becomes `VoxyServerSide`; filename adoption only operates within one folder. A Paper brand swap starting a fresh config folder is an explicit decision, not a new missing-adoption finding. Broad banner prose about cross-brand continuity must be read with that exception.
- Main derives more line values than the support branches. Support branches’ hand mirrors are expressly recorded and currently agree. Lack of identical implementation machinery is not itself a release defect.

## Stale prose, distinct from product bugs

- The `run-neoforge` console instructions still say a NeoForge client is inert because no Voxy build exists: `26.2/test-server.sh:966`, `26.1/test-server.sh:938`, `1.21.1/test-server.sh:944`. Those three lines now explicitly ship with documented working NeoForge client pairings. Update the emitted text to the line’s current pairing/tier, ideally deriving it from the same line data. No runtime code is gated by this message.
- Historical “only 1.21.1 ships NeoForge” / “server-side” comments remain in some shared workflow/build prose even though current flags and publish display values are correct. Treat as documentation cleanup, not as evidence of skipped artifacts.
- Some CLAUDE descriptions still call Zstd flat-shaded on NeoForge; current build code and actual jars nest it alongside SQLite after issue #275. The artifact/source layout is correct.

## Validation limits and next lens

No Gradle task or full release checker was run here, no external publishing/API state was queried, and no Prism/server configuration was changed. The artifact snapshot checks are narrower than a clean rebuild or a live FML launch with another mod nesting the same libraries. Existing tests pin these contracts; parent owns executions. Cross-line serializer behavior, MC callback/field flavor correctness, and per-loader render differences are separate lenses and are not certified by byte-compatible VSS packaging.
