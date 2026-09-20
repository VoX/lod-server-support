# 0.15.0 release preparation — 2026-09-20

All five support lines have passed the exact-version local release preflight. The latest published release on each line is 0.14.0. The proposed 0.15.0 includes the unreleased far-player, compatibility and reliability work as well as the completed P1–P7 improvements. The [proposed notes](release-notes-v0.15.0-proposed.md) are tailored to this checkout’s shipping platforms.

The two Xaero map workarounds are deferred following the user’s complexity/value assessment. First-spawn map gaps and region-boundary shading remain disclosed limitations. They do not invalidate the user’s eight image acceptances or the separate desktop-coexistence acceptance. No further product fix or performance rerun is required by this preparation.

## Release matrix

| Support line | Engine used for build | Shipping loaders | Destination branch / PR | Annotated tag |
| --- | --- | --- | --- | --- |
| 1.21.1 | 1.21.1 | Fabric, Paper/Purpur, NeoForge | `support/mc1.21.1` / #289 | `v0.15.0+mc1.21.1` |
| 1.21.10 | 1.21.10 | Fabric, Paper/Purpur | `support/mc1.21.10` / #290 | `v0.15.0+mc1.21.10` |
| 1.21.11 | 1.21.11 | Fabric, Paper/Purpur, experimental Folia | `support/mc1.21.11-v0.14` / #291 | `v0.15.0+mc1.21.11` |
| 26.1 | 26.1.2 | Fabric, Paper/Purpur, experimental Folia, NeoForge | `support/mc26.1-v0.14` / #292 | `v0.15.0+mc26.1` |
| 26.2 | 26.2 | Fabric, Paper/Purpur, experimental Folia, NeoForge | `main` / #293 | `v0.15.0` |

26.1 Fabric metadata supports 26.1, 26.1.1 and 26.1.2; its Paper/NeoForge artifacts target 26.1.2. NeoForge far-player rendering is available only on 1.21.1. Other shipping NeoForge lines remain best-effort and do not render far players. Folia’s experimental status is unchanged. Only the 26.2 release is marked latest. Current `.github/line.env`, the compatibility catalog and build definitions take precedence over dated historical banners.

## Validation and artifact identities

The [machine-readable preflight record](../implementation/release-preparation-2026-09-20.json) retains all five build heads, log hashes, test counts, original native artifact identities and 30 candidate hashes. All builds use `CI=true` and `-Pmod_version=0.15.0`; the development version in `gradle.properties` remains unchanged, matching the existing tag-driven release process.

Each line ran common tests, the Fabric build (unit and server game tests), Paper tests and shadow JAR, NeoForge build and server game tests, and all LSS/VSS packages. The four newer lines explicitly ran Fabric client game tests on an isolated WSL display. Tier 3 remains unavailable on 1.21.1. Java 21 built 1.21.x and Java 25 built 26.x, with the common module’s Java 21 toolchain preserved. Builds ran sequentially across support lines.

| Line | JUnit identities | Skipped | Fabric / NeoForge server game tests | Client game tests | Release / artifact gates |
| --- | ---: | ---: | --- | --- | --- |
| 1.21.1 | 3,064 | 4 | 78 / 8 | Documented cut | Passed |
| 1.21.10 | 3,056 | 6 | 80 / 8 | Passed | Passed |
| 1.21.11 | 3,064 | 4 | 80 / 8 | Passed | Passed |
| 26.1 | 3,071 | 4 | 79 / 8 | Passed | Passed |
| 26.2 | 3,093 | 4 | 79 / 8 | Passed | Passed |

There are 15,348 reported JUnit identities, 22 documented skips and 15,326 non-skipped results, with no failures or errors; 436 server game tests and four client game-test tasks passed. The log distinguishes tasks executed in this preflight from Gradle’s unchanged `UP-TO-DATE` outputs. Twenty skips are optional experiment/corpus tools; two additional 1.21.10 skips are optional unresolved Sodium golden dependencies. No retry was needed for these release preflights.

`release_check.py --version 0.15.0` and the shipping test/fixture-exclusion gate passed on every line. Thirty LSS/VSS artifacts were built and retained, including maintained NeoForge packages for the two nonshipping lines. The intended distribution is 13 LSS artifacts and their 13 VSS counterparts; the four 1.21.10/1.21.11 NeoForge artifacts are not published.

Every LSS candidate was compared recursively with its retained final native-test artifact. All class bytes and non-version resources match. The only changes are the expected mod/plugin version declarations and Fabric’s nested common metadata/path. VSS equivalence is separately enforced by the existing release checker. The 54 native cases, 42 required target matches and eight user-reviewed captures retain their original artifact SHA-256 values; this is an explicit metadata-only comparison, not a claim that those native cases were rerun on new JAR hashes. V27’s original performance limits remain unchanged.

Raw logs, XML, candidate JARs and comparison scripts are retained locally at `/home/vox/.local/state/lss-project-improvements/release-prep-20260920`. No diagnostic or private rig evidence is automatically uploaded. Existing unrelated working-tree changes and historical untracked rig evidence remain preserved.

## Remaining publication sequence

1. Synchronize the existing feature branches and require fresh CI for their exact new heads. The prior PR checks were green at the September 16 remote heads and are not evidence for later commits. Keep the support-line bases shown above; do not route support lines into main. Validate compatibility/catalog and settings references locally after the preparation commits.
2. Review the proposed notes and approve publication. No tag, merge or publication is performed as part of this preparation. When publishing, merge the five PRs with merge commits, then verify each exact destination commit and its CI. If product/build inputs change, repeat the affected preflight before tagging.
3. Use each line’s notes file for its annotated tag with `--cleanup=verbatim`, and inspect the stored annotation before pushing. Push only the five intended release tags; no smoke tags. The existing workflows publish LSS to GitHub and Modrinth project `lKiXKLvv` using the per-line loader/game-version split.
4. Publish VSS separately through the existing local discipline to `84zcagOb`: bare `0.15.0`, no changelog, unversioned filenames and the same shipping loader/game-version split. `release.yml` does not publish VSS. Do not expose local credentials.
5. Verify all expected versions, loader/game-version metadata, attached artifact identities, rendered notes and latest flags. If publication partially succeeds, recover using the original attached artifacts; never rerun a partially published workflow.

The [release discipline](../history/contributor-guide-before-project-improvements.md#releasing) remains authoritative for tag formatting and recovery. Historical compatibility claims in that document do not override the current release matrix.
