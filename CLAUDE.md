# Contributor guide

This checkout is the Minecraft **26.1** support line. Use **Java 25** for the platform build and **Java 21** for common tests. Actual platform MC targets, shipping and renderer availability are checked by [the compatibility catalog](docs/compatibility.md), with `.github/line.env` and build definitions authoritative. Run client gametests explicitly alongside the Fabric build; preserve this line’s loader and rendering adaptations.

## Commands

```sh
python3 tools/verify/verify.py full --run
bash tools/verify/run-gradle.sh vssJars
python3 scripts/release_check.py
python3 tools/tests/check_artifacts.py
python3 tools/compat/catalog.py validate
python3 tools/compat/catalog.py render --check
python3 tools/verify/verify.py fast path/to/changed/file
```

Fast selection is advisory. Never weaken required build/release gates because a fast selection passed. Run heavy builds and real-server tests sequentially. Every background condition wait needs a deadline and preserved failure evidence.

## Invariants and pinned decisions

- Read the behavior test before changing a pinned decision. Wire fidelity is never tiered; protocol and cross-brand adoption remain unchanged.
- `common` has no Minecraft/loader dependency and emits Java 21 classes. `xplat` is shared source, with narrow loader twins and explicit per-line API seams.
- The server owns generation. Permanent terminal responses and temporary pressure have different retry semantics. Preserve request ownership, receipt release and old-session rejection.
- Receive OFF retires acquisition but preserves committed Xaero native rebuilds. Disconnect/world replacement retires native ownership. Preserve queue locks, budgets and same-dimension replacement guards.
- Settings retain defaults, sentinels, upgrade/adoption behavior, privacy, clamps, ownership and applied-but-unsaved feedback. Correlated changes validate on scratch and publish consistently before side effects.
- Best-effort support and Folia’s experimental correctness status are distinct. A bounded soak does not remove the experimental label. Neo shipping, terrain consumers and far-player rendering are separate capabilities.
- Never operate the user’s Windows foreground for an unattended test. Use private-display rigs, owned process identities, exact artifacts and the shared harness lock. Preserve normal servers, personal profiles/worlds and credentials. No automatic diagnostic/evidence upload.
- Use isolated branches/worktrees for ports. No reset of dirty trees, blind keep-ours, target-branch merge, push, deployment or publication is implied by local validation.

## Known Test Flakes & Environmental Failures

[The canonical flake catalog](docs/operations/test-flakes.md) preserves exact signatures and actions. A different assertion is a regression, not permission to rerun away a failure. Preserve first-attempt XML/logs before any justified retry and record actual test identities/counts.

## Architecture

[Current ownership](docs/architecture/ownership.md), [version surfaces](docs/planning/per-version-surfaces.md), [test placement](docs/testing/test-placement.md), [settings reference](docs/reference/settings.md), and [implementation ledger](docs/implementation/project-improvements-ledger.md).

## Local Test Servers

Use [maintained disposable rigs](docs/operations/disposable-rigs.md) for automated testing and [support-line tooling](docs/operations/support-lines.md) for exact dependency/ref checks. Windows client-visible loopback may require `[::1]:port`; configure bind and visible endpoints separately. Never select a normal server for cleanup merely because its port matches.

## Releasing

Existing publishing and branding rules are unchanged. Follow the [preserved release discipline](docs/history/contributor-guide-before-project-improvements.md#releasing) and current release scripts. Do not rerun a partially published release; recover from its original attached artifacts. This improvement task does not authorize merges or publication.

The [previous complete guide](docs/history/contributor-guide-before-project-improvements.md) preserves historical explanations and decisions. Its dated compatibility claims do not override current build definitions/catalogs. Keep stable links and historical evidence intact.
