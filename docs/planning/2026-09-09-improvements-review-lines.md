# Independent review: compatibility, support lines and rigs

Date: 2026-09-09. Reviewed the implementation plan's §§1–5, 8, 13–14, with dependency checks against the other packages. Read-only evidence came from the five updated `lss-lines` worktrees, particularly their `.github/line.env`, 26.2's build workflow, canonical surfaces and live-profile inventory, and 1.21.1's Astra ledger. No builds, runtimes, network operations or product changes were performed.

Disposition: the scope is appropriate and retains the excluded recommendations, but the following amendments are needed before implementation. These are design decisions missing from the plan, not claims that the proposed implementation already contains bugs.

## R1 — Define where cross-line authority lives and how updates propagate

**Required; §§4–5, 13–14.** `config/compatibility/` is proposed inside a repository with five independently advancing branches. The plan does not say which copy owns aggregate facts, how copies propagate, or how one line can update its facts without making another branch's aggregate documentation falsely current. “All five line facts match actual definitions” has no durable meaning without the exact source-ref set and authority rules.

Specify one aggregate authority and immutable source-ref manifests, or a line-local fact model with aggregate views generated from an explicit five-ref snapshot. State which files are edited, imported and generated, and the port/update procedure. Every aggregate view must carry its source identities; a branch-local view must not imply an unexamined sibling HEAD is current. Define schema/tool compatibility during staggered ports. Keep `.github/line.env` and existing build definitions authoritative for their respective values; do not generate replacement release inputs from the catalog.

The real data needs platform-specific build targets and declared compatibility ranges: 26.1 builds Fabric against 26.1 while Paper and NeoForge target 26.1.2. Main derives some release checks from `line.env`, while support-line comments still require hand mirrors. Import/check those existing contracts without broadening this into their migration.

Acceptance addition: update only one line's renderer or build target, generate against a fixed ref set, and prove that sibling historical results stay dated and aggregate freshness changes explicitly.

## R2 — Make cross-line CI reproducible and compatible with sequential ports

**Required; §§4–5, 13–14.** “CI acquires a pinned set of refs” needs a concrete contract: manifest location, full commit IDs, candidate substitution, fetch depth, missing-object behavior and update ownership. Ordinary checkout is shallow, and comparing only preselected sibling commits can miss the PR being tested. Conversely, requiring shared equality with unchanged sibling branches would block the first valid port.


Define the comparison baseline separately from the proposed port batch. CI must include the actual candidate SHA, record the five resolved identities, fail explicitly on unavailable inputs, and distinguish expected outstanding ports from unexplained shared divergence. Specify when the batch is incomplete versus accepted. Do not use mutable branch tips as unrecorded inputs or demand simultaneous merges.

The current 26.2 build workflow excludes `**.md` and `docs/**`. A generated-section freshness check added only inside that workflow will not run on manual documentation drift. Add a lightweight validation workflow or adjusted trigger that covers generated docs and catalog/tool inputs without forcing Minecraft builds for ordinary docs edits.

Acceptance addition: PR candidate differs from the pinned baseline; missing shallow object; sibling tip moves after manifest capture; first port precedes its siblings; generated README changes alone. Each must produce the specified reproducible result.

## R3 — Specify adapted-file coverage beyond flavor checks

**Required; §5.** A narrow flavor check can prove the correct API name or native flag while missing an unrelated logic deletion elsewhere in the same adapted file. The current acceptance example of a deliberately edited shared file does not cover that blind spot. Patch identity also cannot establish semantic completion of an adapted cherry-pick.

For every adapted file, record named axis regions or semantic contracts, residual shared-logic comparison/review requirements, and explicit unverified status for changes not covered by a checker. A permissive classification must not certify arbitrary changes. Require stable surface IDs plus line/platform values and test/evidence references. Include non-Java axes: real gamerule names and values, removal of `spawnChunkRadius` beginning at 1.21.10, per-platform world layouts, per-loader native packaging, and corpus provenance. Main's surfaces document already records these distinctions.

For `lines plan/prepare`, define merge-commit handling, prerequisite representation, adapted-patch equivalence and resumability after conflicts. A dependency order is not derivable reliably from a raw commit range alone. Never infer “already applied” merely from subject text or path overlap.

Acceptance addition: preserve the correct flavor token but delete shared behavior inside an adapted file; swap native V20/prefixed behavior; use the right modern gamerule name with the wrong disabled value; omit a prerequisite of an adapted pick. These must fail or explicitly require review before port acceptance.

## R4 — Separate dependency locks, candidate artifacts and validation claims

**Required; §§4, 8, 13–14.** P2 defines exact profile locks while P4 must repeatedly test newly built candidates. Specify an immutable dependency profile plus a run manifest binding exact LSS/VSS and fixture artifacts, loader/server distribution, scenario/checker version, world seed/snapshot digest, effective configs, JVM flags and backend. Candidate injection must not silently edit a published dependency lock. Record both original and transformed identity where Connector changes the load route.

Metadata constraint validation must use the applicable loader's range/dependency semantics, including nested libraries and explicit Connector routes; Fabric metadata in a NeoForge profile is not inherently invalid. Metadata compatibility remains separate from runtime success. Define validation by feature and scenario, with explicit rejected/pending outcomes and a deterministic current-result selection rule. A historical pass must not mask a later failed trial of the same exact identity. Keep the accepted Xaero-only legacy Neo profile distinct from the rejected native Voxy 0.2.9 pairing; neither invalidates the other's narrower observation.

Document offline dependency materialization: exact missing hashes and source locations, an explicitly invoked fetch stage, verification before cache admission, and behavior if an artifact is no longer obtainable. Do not put mod selection or publishing in this stage.

Acceptance addition: substitute a candidate with the same filename but different bytes, change only a fixture/checker/config, load through the wrong Connector route, and replay an older passing result after a rejected newer attempt. Current-validation claims must respond correctly.

## R5 — Resolve ownership and authentication boundaries for Windows Prism

**Required; §8.** The existing lock is concretely `/tmp/lss-harness-$UID/port-25565.lock`, checks port 25565, and uses inherited POSIX file descriptors; supervision lives in `scripts/lib/owned-process.py`. A Python runner with configurable ports and a Windows Prism backend cannot assume those ownership semantics transfer automatically.

Specify how new and existing runners acquire the same coarse resource lock before staging, and how configurable endpoints interact with the existing fixed-port check. Document the boundary between an owned launch and attaching as an observer to a preexisting Prism process. If the launcher can forward to an existing Windows process, descendant ownership cannot be assumed: require verified per-run client ownership or leave stop explicitly unable to terminate it. Do not terminate shared launchers.

Independent clones strip inherited accounts, yet authenticated launches still need a deliberate account mechanism. State how the user selects an existing authenticated launcher context or a supported disposable test identity without copying credentials into profiles, commands or exported evidence. Treat unavailable interactive/authentication capability as a declared precondition. Add tests for launch forwarding, preexisting clients, interrupted cross-OS ownership recovery, and the normal-server case. Preserve the bracketed IPv6 connection mechanism already established by the ledger.

## R6 — Close dependency and final-acceptance ambiguities

**Required; §§1, 8, 13–14.** Make the dependency graph executable in stages: P0/P2 schema and profile contract → P3 inventory plus P4 core → P4 verified fixtures → P1/P5 runtime acceptance → P6b measurements → P7b measured presets. P6a selection also depends on the P0 impact inventory and should consume P3 classification once available. P3's representative dry run should occur early enough to validate the port model before large packages accumulate; its final all-line check remains at handoff.

P4 acceptance names both 1.21.1 loader lifecycle/render profiles but does not explicitly enumerate all four Sodium generation × loader combinations that §§7 and 14 require. Name stable profile IDs and feature/scenario applicability, including Xaero-only legacy Neo coverage, no-consumer controls where relevant, two distinct 1.21.11 C2ME arms, and the intentional renderer stubs. Map each final acceptance row to a profile and concrete checker or human observation so one generic smoke cannot satisfy unrelated gates.

Clarify artifact validation for maintained-but-unshipped Neo modules: validate their built packaging and fixture exclusion without changing release-required family sets. Shipping flags, release workflow behavior, tags, VSS publication and artifact promotion remain outside implementation scope.

Finally, identify evidence locations precisely: the aggregate Astra implementation ledger inspected here is in the updated **1.21.1** worktree; the same relative path is absent in 26.2. Cite the source line/ref rather than expecting the ledger on every branch. This prevents baseline discovery from silently falling back to the older current checkout.

## Strengths to retain

The plan already separates shipping from rendering, forbids nonexistent 1.21.1 Folia/Tier 3 gates, preserves both 1.21.11 C2ME profiles, rejects metadata-only live claims, keeps port preparation isolated, preserves evidence from failed attempts, and excludes publication redesign. These constraints are well chosen; the amendments above make their enforcement concrete.
