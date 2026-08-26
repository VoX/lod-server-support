# LOD store: registry-permutation tolerance (v0.13.1)

Status: IMPLEMENTED on main (PR #255) with the 1-Fable plan review (§7) and the
1-Fable + 3-Opus fix review (§8) folded → backports to all four support lines →
v0.13.1.

## 1. Problem (diagnosed live, 2026-08-26)

User report (VSS 0.11.0, fabric 26.1.2): the LOD store drops and rebuilds on EVERY
restart with `LOD store: schema/wire/version drift — dropping and rebuilding the
store (derived data, never migrated)`, with no changes to world or mods.

Reproduced with the reporter's 107-mod pack and their exact published VSS jar; the
meta-table diff across a boot pair isolated the flipping key: `registry_fingerprint`,
block-state half only. Registry dumps across two boots: **83,687 identity strings,
sorted diff EMPTY, unsorted diff 3,216 lines** — a pure per-boot PERMUTATION.
Culprit: **VisualWorkbench** registers its generated per-table blocks in
hash-iteration order, which shuffles every boot and shifts the global ids of
everything registered after it. Vanilla is unaffected (region files store names;
sessions re-sync ids at login) — the only victims are systems persisting
boot-scoped ids, which is precisely what the fingerprint was built to protect.

Baseline repro control: the same jars WITHOUT the pack (fabric-api + c2me only)
persist the store across restarts — both LSS and VSS jars.

**Second, independent instance of the same defect (review MAJOR-2):**
`XrayMaskFilter.MaskSet.fingerprint()` FNV-hashes the `hiddenByStateId` boolean
array — indexed by GLOBAL STATE ID (xplat XrayMaskFilter ~line 125; Paper twin
identical). On any masked server (`xrayObfuscation=on`, or `auto` + a detected
engine) a pure permutation flips every dimension's mask fingerprint and the
startup sweep drops every dimension's rows ("x-ray mask changed for <dim>") even
after the meta-level fix. Both defects ship in v0.13.1 or the headline claim is
false for exactly the big-modpack population being fixed.

## 2. Why relaxation is safe now (and where it is NOT)

The order-sensitive fingerprint exists because wire-v19 store rows are MC-native
section bytes embedding global ids; serving (or translating) them after an id
permutation produces wrong blocks. Since v0.10.0 the store deposits **wire-v20
bodies**: identity-dictionary addressed (block-state AND biome identity strings,
per-section palettes of dictionary indices), designed to decode across entire MC
versions — strictly stronger than permutation-proof. Every row carries `wirefmt`
(19 native-legacy / 20), so the two populations are distinguishable — and the
migration bookkeeping distinguishes them in O(1) (§3.3).

Therefore:
- a pure permutation (identical identity SET, different order) is harmless to a
  store containing ONLY wirefmt=20 rows;
- it remains UNSAFE while any wirefmt=19 row exists (the serve/migration
  translator maps legacy bytes via the CURRENT boot's registries);
- any CONTENT change (identities added/removed/renamed) must keep dropping —
  that is real registry drift, the case the guard was built for;
- masked bytes are post-mask CONTENT: the mask's semantics are "which block
  IDENTITIES are hidden below what Y" — its fingerprint has no business being
  id-indexed either (§3.5).

## 3. Design

### 3.1 `RegistryFingerprint.contentOf` (common)

New static beside `of`: identical FNV chain over BOTH lists **after sorting a
copy of each** (natural String order). Format `bsc:<hex>/bioc:<hex>` — distinct
prefixes so the two hash kinds are un-confusable, format load-bearing like `of`'s
(a bare count must stay un-representable). `of` is byte-for-byte untouched.

### 3.2 Environment + suppliers (common + xplat + paper twin)

`SqliteLodStore.Environment` gains `String registryContentFingerprint` (new ctor
arm mirroring the existing telescoping pattern — the convenience arms default it
to `""`, which the ladder treats as UNPROVABLE, never as a match; §3.3).
`RequestProcessingService.storeRegistryFingerprint` refactors to build the two
identity lists ONCE and return both strings; `PaperRequestProcessingService`
textual twin identically.

Contract-test reality (review MINOR-5): the pins are
`StoreEnvironmentContractTest` (fabric) and `PaperStoreEnvironmentContractTest`
— SOURCE-REGEX argument-presence pins, currently requiring only
`storeRegistryFingerprint(server)` in the Environment call. Both regexes MUST be
extended to require the content-fingerprint argument too, or a backported
service left on the old call shape passes its contract test while the content
fingerprint silently defaults `""` and the fix is disabled. The return-shape
refactor changes the call text — update both regexes to the new shape in the
same commit. Also fix the stale javadoc name (`StoreEnvironmentWiringTest`) in
RequestProcessingService.

### 3.3 The meta ladder (`openOrRecreateWriter` / `metaMatches`)

`metaMatches` is replaced by a reasoned result (match / adopt / permutation-keep
/ drop-with-named-reason):

```
core = schema_version, store_layout, wire_format_version, mc_version, codec
if any core key differs (or is absent)  -> DROP, log the differing key name(s)
                                           (neutral header: "store metadata drift
                                           [<keys>]" — a version bump is not
                                           "identity drift"; review MINOR-6)
if meta.registry_fingerprint is ABSENT  -> DROP, log "pre-fingerprint store"
if registry_fingerprint matches:
    if registry_content_fingerprint absent from meta (pre-0.13.1 store)
        -> ADOPT: write the new key in place, keep the store, no drop
    -> OPEN
else (ordered mismatch):
    provable = env.contentFp non-empty AND meta.registry_content_fingerprint
               present AND non-empty            (review MAJOR-1: "" == "" is a
                                                vacuous match — empty/absent on
                                                EITHER side is unprovable)
    if provable AND contentFp matches AND !hasLegacyRows():
        -> KEEP: log "registry ids permuted (same content) — store kept
           (v20 rows are identity-addressed): N rows across D dimension(s)"
           (counts from the loaded dims map — the live gate greps them; NIT-7);
           writeMeta refreshes BOTH registry keys, committed before first serve
    else -> DROP, log which condition failed:
        registry content drift | permutation with legacy (wirefmt=19) rows
        present | permutation unprovable (no content fingerprint)
```

`hasLegacyRows()` is **O(1)** (review MAJOR-3 — the naive
`SELECT … WHERE wirefmt=19 LIMIT 1` walks the blob-leaf b-tree of every dim
table on the server thread, every boot, on exactly the affected servers):
`"1".equals(meta.get("migrate_pending"))`. Exact conservative proxy: wirefmt=19
rows are produced ONLY by the lazy 3→4 upgrade, which sets `migrate_pending=1`
in the same transaction; deposits always stamp 20; the key is deleted only by
`finishMigration` after the walk exhausts every dim. Pending-but-actually-
complete answers true → DROP — the safe direction for derived data. (No
lods-table query at all, so the wirefmt-column-existence question is moot; the
core-key gate has already guaranteed schema=4 by the time the probe runs.)

The drop message keeps the `LOD store:` prefix and the "(derived data, never
migrated)" tail; the middle names the reason. Nothing pins the old exact message
(review-verified: repo-wide, tests + check_soak.py + scenarios clean).

### 3.4 Compatibility

- 0.13.0 → 0.13.1 upgrade, stable registry: silent ADOPT, no rebuild.
- Upgrade, unstable registry (the reporter): ONE final rebuild (permutation
  unprovable against old meta), then stable.
- Downgrade 0.13.1 → 0.13.0: the extra meta key is ignored by the old equality
  compare; the refreshed ordered fingerprint is the newest boot's. Behavior
  identical to today. No schema bump: STORE_LAYOUT and SCHEMA_VERSION unchanged.
- Masked servers additionally pay a ONE-TIME per-dimension row drop at first
  0.13.1 boot (the mask fingerprint VALUE changes; §3.5) — same "one final
  rebuild" story, then stable.

### 3.5 The mask fingerprint (review MAJOR-2)

`MaskSet.fingerprint()` re-derives from the mask SEMANTICS instead of the
id-indexed array: FNV over the SORTED identity strings (`String.valueOf(state)`
— the registry fingerprint's own convention) of the hidden states, then the
cutoff height — precomputed at construction (both factories hold the
`BlockState` objects; `resolve` and `fromStates`). Permutation-stable, still
flips on any real semantic change (states added/removed/renamed, cutoff moved).
`PaperXrayMaskFilter` twin identically (masked BYTES are untouched — the golden
corpus fixture is unaffected; only the staleness key changes). The per-boot
`transient:` nonce for non-terminal mask states stays exactly as is (deliberate
conservative drop while the mask is undetermined). Extract the string-list →
long computation into a static seam so Tier 1 pins order-insensitivity and
content-sensitivity without needing to permute a real registry. Accepted
residual: `chooseReplacement` breaks exact filler ties on the global state id,
so a KEPT masked row built under one boot's ordering can differ from a fresh
serve's filler pick — cosmetic (both candidates are real non-hidden states),
healed by the next re-serve.

## 4. Tests

- `RegistryFingerprintTest`: contentOf format pin; permutation-INSENSITIVE pin
  (shuffled lists, equal hash); content-change pin (one identity edited flips
  bsc, one biome flips bioc); `of` pins unchanged.
- `SqliteLodStoreTest` (or a sibling `SqliteLodStorePermutationTest`), driving
  reopen cycles with controlled Environment fingerprints (the existing seams:
  arbitrary-string fingerprints, open/shutdown reopen, raw-SQL meta/row surgery
  per `SqliteLodStoreMigrationTest`'s fixture):
  1. v20-only store, permuted order + same content fp → rows SURVIVE, meta
     holds the refreshed fingerprints;
  2. same permutation + `migrate_pending=1` planted (with a wirefmt=19 row for
     realism) → DROP;
  3. content drift → DROP;
  4. pre-0.13.1 meta (content key deleted) + matching order → ADOPT (rows
     survive, key appears);
  5. pre-0.13.1 meta + permuted order → DROP (unprovable);
  6. EMPTY env content fingerprint + ordered mismatch → DROP (the MAJOR-1
     vacuous-match guard — this is what keeps the existing
     `registryFingerprintDriftDropsAndRebuildsTheStore` pin green UNCHANGED;
     that test must not be touched);
  7. core-key drift still drops (mc_version edit) with the key named.
- Mask fingerprint seam: order-insensitivity (shuffled identity list, equal
  long) + content/cutoff sensitivity; fabric + paper twins if the seam lands
  per-module (prefer hoisting the static into common so ONE test pins both).
- Contract tests: both Environment regexes extended (content argument
  required); Paper twin symmetric.

## 5. Backports + release

Store code is line-invariant `common/` + the xplat/paper suppliers + the two
mask filters; the port to all four support lines is mechanical (no per-line
flavor expected; 1.21.1 builds with Java 21 — note `Identifier` vs
`ResourceLocation` on 1.21.x for the mask filter if the diff touches imports).
Order: main PR → 1 Fable + 3 Opus fix review → fold → backport per line
(direct push to support branches; CI on push) → per-line pre-flights with
`-Pmod_version=0.13.1` + release_check → notes files (short user bullets):

> **LOD store no longer resets on every restart with certain mods** — mods that
> shuffle the block registry order each boot (e.g. VisualWorkbench) made the
> store discard itself at startup; it now keeps the store when ids merely moved.
> One rebuild happens on the first start after updating.

plus a Bug Fixes bullet for the named-cause log line. Staged tag commands mirror
the v0.13.0 run sheet (annotated, `--cleanup=verbatim`, pushed one at a time,
main first). Tags NOT pushed at prep.

## 6. Validation

- Unit suites per §4 on every line.
- Live: the reporter's pack is staged in the 26.1 rig; a boot pair on the 26.1
  backport build must show the drop ONCE (upgrade) then the `store kept … N
  rows` line on every subsequent boot.
- The vanilla boot-pair control (store persists, no new log lines) on main.

## 7. Plan review fold (1-Fable, 2026-08-26)

Verdict NEEDS-REWORK; all findings folded:
- **MAJOR-1** (vacuous `""`==`""` KEEP; would red or corrupt the
  `registryFingerprintDriftDropsAndRebuildsTheStore` pin) → the `provable`
  guard in §3.3 + test 6 + the pin listed required-unchanged-green.
- **MAJOR-2** (id-indexed mask fingerprint re-drops every dim on masked
  servers) → §3.5 + §3.4's one-time-drop note + §1's second-instance record.
- **MAJOR-3** (hasLegacyRows full blob-b-tree scan per boot) → the O(1)
  `migrate_pending` proxy.
- **MINOR-4** (wrong wirefmt-column-existence reasoning) → moot under the
  proxy; core-key-gate note kept in §3.3.
- **MINOR-5** (wrong test name; regex wouldn't catch one-sided twin miss) →
  §3.2 rewritten with the real test names + mandatory regex extension + the
  stale javadoc fix.
- **MINOR-6** (drop-reason taxonomy mislabels pre-fingerprint/schema-3 stores)
  → neutral core-key header + explicit "pre-fingerprint store" rung.
- **NIT-7** (KEEP log lacked the row count §6 greps) → count added to the line.

Review-verified non-defects (for the implementer): KEEP/ADOPT crash-window safe
(content compare re-decides identically next boot); `INSERT OR REPLACE` meta
refresh leaves `migrate_*` bookkeeping untouched; nothing re-reads the registry
keys post-open; deposit path stamps wirefmt=20 literal (closes the TOCTOU);
store-stamp/header-fresh rungs + backfill done-marks permutation-independent;
old drop message unpinned repo-wide.

## 8. Fix review fold (1 Fable + 3 Opus, 2026-08-26) + as-built deviations

Verdicts: Fable FOLD-MINORS (one MAJOR mandatory), store-lens Opus NEEDS-REWORK
(scoped to the same MAJOR), mask-lens Opus FOLD-MINORS, tests-lens Opus
FOLD-MINORS. Everything below is folded on the PR branch:

- **MAJOR (Fable + store Opus, independently): the `migrate_pending` proxy's
  invariant did not hold.** (a) The admin drop-all's meta clear ran
  unconditionally even when shutdown interrupted the drop loop — despite its own
  m16 comment — leaving flagless wirefmt=19 rows; the clear is now gated on
  actual completion, making the comment true. (b) The walk's documented
  swallowed-delete-failure residual (a 19-row stuck behind the watermark)
  outlived `finishMigration`'s clear; the finish now VERIFIES (one probe per
  dim, once per store lifetime, batcher thread) and writes a permanent
  `migrate_residual` marker that `legacyRowsPossible` also reads. End-to-end
  pinned in `SqliteLodStoreMigrationTest.residualNineteenRowBehindTheWatermark…`.
- **MAJOR (tests Opus): the of()/contentOf delegation was unpinned** — wiring
  the content slot to `of` compiled and passed every suite while re-enabling
  the every-boot rebuild. Folded together with the three-reviewer "walk runs
  twice" MINOR: the services now derive BOTH fingerprints from ONE
  `storeRegistryIdentity` walk at the Environment call, and both contract
  regexes pin the `RegistryFingerprint.of(...)`/`.contentOf(...)` delegation at
  that call plus the single-walk assignment.
- KEEP hardening (three reviewers): `writeMeta` commits BEFORE the log line and
  the row-count summary is contained — a cosmetic `count(*)` throw can no
  longer fall into the catch-all rebuild of a store just proven keepable.
- `MetaVerdict` test seam (`lastMetaVerdictForTest`) — every ladder test now
  asserts WHICH rung fired and the drop details (incl. the named core key),
  de-vacuuming `permutationWithPendingLegacyRowsStillDrops` (its planted CRC
  19-row nulled `get()` regardless; the meta witness + verdict pin close it).
- Mask fingerprint wiring pins in BOTH `XrayMaskFilterTest` twins (fingerprint
  == the shared seam over the hidden states' identity strings; a one-sided
  backport dropping the identity collection would flatten every fingerprint —
  an x-ray leak — with all other suites green).
- Test 4.6 landed as the both-directions
  `emptyContentFingerprintOnEitherSideCannotProveAPermutation`.
- Environment compact ctor null-normalizes both fingerprints; the mask seam
  skips null elements (throw-free serve choke points); FQN style NITs; stale
  `metaMatches` prose renamed; CLAUDE.md store bullet updated; §3.5 records the
  tie-break residual.

Accepted (recorded, not fixed):
- **Legacy escaped stores**: a store that reached the flagless-19-row state
  under a PRE-0.13.1 jar and upgrades via ADOPT carries no marker; a later
  pure permutation on it would KEEP and mistranslate its residual 19-rows.
  Requires a rare pre-0.13.1 double-fault/interrupted-drop history AND a
  usually-stable-then-permuting registry; bounded to the residual rows;
  self-heals via any content change, a manual `store invalidate all`, or the
  rebuild any of those triggers. Closing it would cost a full blob-b-tree scan
  at boot — the exact cost MAJOR-3 rejected.
- `keptRowsSummary`'s per-boot O(rows) ts-index scan on permuted boots (never
  blob leaves; ~100s of ms worst case) — the §6 live gate greps the counts.
- Test-helper placement stays section-local beside the ladder tests (reads
  better than hoisting into the generic plumbing block).
