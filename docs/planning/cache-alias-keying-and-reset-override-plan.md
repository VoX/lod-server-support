# Two-axis cache identity (alias × world) + the reset storage-override escape hatch

Status: PLANNED, v3 — the two-axis synthesis (user direction 2026-08-27),
twice 2-Fable-reviewed: the v1 alias-only round is §8, the v2 two-axis round is
§9; both folds are applied in the body below. Replaces the goals of external PR #243, whose seed-AS-identity
approach was found unshippable by the 1-Fable + 4-Opus review (§1.2); v2
re-adopts the PR's seed insight in the one position where it is safe — as a
SUB-partition under the address, never as the identity.

## 1. Intent, extracted from PR #243 (and its rework)

Two genuine user problems:

**Problem A (multi-address cold fill).** One server reachable at several
addresses cold-fills the same world once per address: LSS's stamp cache
(`ColumnCacheStore`) and the consumer's store (Voxy: `.voxy/saves/<ip>`,
address-derived) both key by the typed address.

**Problem A2 (world identity under one address).** The inverse hazard the PR's
seed idea actually solves, kept in v2: one ADDRESS is not one WORLD. A server
that resets its map to a PRE-GENERATED world hands out chunks whose save
stamps are OLDER than the client's stamps from the previous world — every
ts>0 re-declaration answers `up_to_date` against terrain the client has never
seen (stale render, healing only as chunks are edited; a fresh-generated reset
self-heals by wall clock, a pre-generated one does not). Likewise multi-world
rotations under one hostname smear every world's stamps into one bucket.

**Problem B (unactionable declined wipe).** When `/lss reset` declines the
Voxy wipe on a storage-override mismatch (the stage-D fail-safe), the user
gets one warn naming neither root and no deliberate way to proceed.

Design principle, kept from the PR author's own analysis and load-bearing
throughout: **LSS's cache partition must never be COARSER than the
consumer's** — stamps answering "fresh" against an empty consumer store is a
permanent hole no self-heal reaches.

### 1.2 Why the seed must be a sub-key, not the key

The five-agent review of PR #243 established: as the whole identity, the seed
is a server-attested, unauthenticated, NON-UNIQUE key — a forged or merely
shared seed lets one server read and poison another server's bucket; no
shipped consumer partitions seed-first (Voxy is address-first), so the
partition went coarser; and the PR's seed-named deletion fired from plain
`/lss reset` switch-independently. All three objections are properties of the
seed's POSITION, not the seed: under the address (`(address, seed)`), a
server-chosen seed can only sub-partition that server's OWN namespace —
cross-server reach is unrepresentable, a shared seed is disambiguated by the
address, a rotating seed degrades to cache thrash on that one server ("slower,
never wrong" becomes actually true), and the pair exactly matches Voxy's real
partition (`address × worldid(seed, dim)`), so the coarseness hazard on the
world axis vanishes by construction, stock Voxy included.

### 1.3 The seed is already public — no secret leaves the server

The client never sees the real world seed and this plan does not change that.
Vanilla's login/respawn packets carry `BiomeManager.obfuscateSeed(seed)` — a
sha256-derived, one-way hash truncated to a long — to EVERY vanilla client;
`ClientLevel` stores it as `BiomeManager.biomeZoomSeed`. LSS reads that
already-public value off the live client with one accessor mixin (verified
byte-for-byte on 26.2 and portable to all four support lines in the PR #243
review) and adds NO wire surface, NO server change, and NO new information a
vanilla client was not already given. The bucket name renders it as
`world-%016x` (lowercase hex, `Locale.ROOT`, sanitize-invariant).

## 2. Design A: the two-axis cache key

### 2.1 The key

For a remote session the stamp-cache bucket is a FLAT directory name composed
of two axes:

```
<addressComponent> [ ".world-" <16 hex> ]
```

- **Address axis**: `sanitizeForFilePath` of the raw typed address — or of the
  alias group's canonical entry (§2.2). Unchanged semantics from main when no
  group matches — EXCEPT (§9 fold) a raw address whose sanitized form ends in
  the reserved `.world-<16hex>` tail (case-insensitively) has the tail
  escaped at key build: a hostile or odd server-list entry must not occupy
  another server's seeded-bucket name (writes there would poison the victim's
  stamps) nor sit inside its reset glob.
- **World axis**: the obfuscated-seed sub-key, appended when the session has
  one (§2.3); absent otherwise, which leaves the bare legacy name — the
  fallback IS the current layout.

Flat composition (a suffix, not a subdirectory) keeps `ColumnCacheStore`
untouched: the whole decision lives in the key STRING built at the single
assignment point (`ClientNetGlue.createRequestManager`), and dimension
partitioning inside the bucket is unchanged — so the effective partition is
`(address, world, dimension)`, exactly Voxy's `(address, worldid(seed, dim))`.

`/lss diag` gains a `Cache:` line showing both axes and the reason branch:
`key=<bucket> (alias group|address; world=<hex>|none — <reason>)`. One
per-connection INFO logs the same.

Latching (reshaped by the §9 fold — BOTH v2 reviewers independently broke the
original connection-wide latch): only the ALIAS decision is latched, set at
first manager build and RESET IN `ClientSessionGate.onJoin` (the one event
that reliably brackets a play session — a play→config reconfiguration fires
neither loader's disconnect, so disconnect is the wrong anchor). The WORLD
sub-key is never latched: it derives FRESH from the live level's
`biomeZoomSeed` at every manager build AND at every dimension/cache-phase
entry — per-world seeds arrive via respawn packets and dimension changes do
not rebuild the manager, so each dimension's save/load cycle uses its
entry-time sub-key (the old dimension saves under the key it loaded with; the
new one reads fresh). The previous sub-key carries forward ONLY when the
fresh read is unreadable — never across a readable different seed. This, not
the static claim, is what actually equals Voxy's partition: Voxy re-derives
its world id at every Level construction, and a latched key would have been
COARSER than Voxy across every re-handshaking backend switch and every
multi-world rotation (§9 M-A1/M-B1 — the exact hole the design principle
forbids, on the headline topology).

### 2.2 The alias axis (config, corroboration-guarded — v1 §2.2 post-fold,
unchanged in substance)

`cacheAddressAliases`: groups of addresses; the first entry is canonical;
membership matches normalized (trim + lowercase ONLY — no default-port strip:
SRV resolution makes `a.com` and `a.com:25565` potentially different servers).
The canonical BUCKET is the first entry's RAW spelling sanitized (warm-cache
adoption holds only for the exact historical spelling — documented). Load
validation rejects: empty/whitespace entries, `unknown`, `local:*`, `realms`,
entries sanitizing to `_`, entries containing a `.world-` suffix pattern
(reserved for the world axis), port-bearing CANONICALS (voxy-extra substitutes
the canonical verbatim into a path; a surviving `:` breaks Voxy on Windows and
splits voxy-extra's own store), cross-group duplicates, and colliding
canonical buckets — a rejected group drops whole with one WARN.

The alias applies per connection only after the CORROBORATION check, because
aliasing LSS alone makes the ADDRESS axis coarser than a non-aliased consumer
(the world axis needs no such guard — §2.3):

- The probe (`VoxyCompat.observeStorageDirName()`) resolves in its OWN
  two-handle domain (`getInstance` + `getStorageBasePath`, never
  `initResetDomain` — its renderer rung is the known-unstable name), own log
  line, null on any failure.
- `AliasCorroboration.evaluate(voxyConsumerRegistered, observedDirName,
  connectAddr, canonicalRaw, group)` — pure, fixture transcribed from
  voxy-extra's actual mixin (verbatim `resolveSibling(listFirst)`
  substitution, exact case-sensitive matching, no munge):
  - Voxy absent (no Voxy consumer registered) → APPLY as configured (the
    pure-API user's documented risk).
  - Voxy present but unprobeable → FALL_BACK + WARN (fail closed).
  - Observed dir equals the canonical VERBATIM or Voxy's `:`→`_` munge of it
    → corroborated → APPLY.
  - Observed dir equals the connect address's munge (≠ canonical) →
    FALL_BACK + WARN "install/configure voxy-extra LoD Mirror with the same
    group — and make the FIRST entry of both lists identical".
  - Matches neither → FALL_BACK + WARN.
- While the Xaero map bridge is ARMED (flag ∧ installed ∧ resolved) and a
  group matches → FALL_BACK + WARN (Xaero's world id is per-address; no
  aliasing exists for it). NeoForge wording notes no consumer-side aliasing
  exists for that loader (voxy-extra is Fabric-only) — Problem A stays unmet
  there, honestly.

Fallback is the safe direction: finer costs re-downloads, never holes.

### 2.3 The world axis (automatic, default on, kill switch)

`useWorldSubBuckets` (client, default true — repo convention: new machinery
ships enabled with a kill switch; false = bare legacy naming, byte-identical).

Sub-key derivation is a pure predicate (`WorldSubKey`, the PR's good
pure-Context shape, adopted): remote session AND not a Realm AND no
integrated server AND seed readable AND seed != 0 → `world-%016x`; anything
else → none (bare bucket). The seed read is the contained
`AccessorBiomeManager` accessor (§1.3); a missing mixin (a loader config
drift) degrades silently to bare buckets — safe, and pinned by the
SeedAccessorContractTest shape from the PR (both loaders' configs + the
reflective field pin, adopted with attribution).

Why no corroboration on this axis: `(address, seed)` can only be FINER than
the consumer's partition. Stock Voxy is `(address, worldid(seed, dim))` —
equal. A consumer ignorant of worlds entirely (address-only) would be COARSER
than LSS here, which is the safe direction for LSS's stamps (worst case: LSS
re-asks after a world reset and the consumer already had the data — a
redundant serve, never a hole). The Xaero bridge is per-address — same safe
direction. This asymmetry (guard the alias axis, not the world axis) is the
design's load-bearing simplification; §9's reviewers should attack it.

**Legacy adoption (no cold refill on upgrade).** First seeded session for an
address with a bare bucket AND **no `<addr>.world-*` sibling of any seed**
(the §9 M-A2 fold: the original "this seed's bucket absent" condition
re-fired after every reseed, swallowing lobby/seedless residue into the new
world's bucket — adoption happens once EVER per address component): MOVE the
bare bucket into `<addr>.world-<hex>`. The move is queued as the FIRST task
on the cache IO thread, ahead of the session's first `loadStateAsync` (the
store's single-FIFO ordering is what keeps a prior session's queued save from
re-creating the source mid-move); a failed rename logs one INFO and the
session uses the (empty) seeded bucket — never the bare one — retrying next
session. The adoption source is the SAME address component the key uses
(canonical when aliased; a warm connect-spelling bare bucket is deliberately
not adopted cross-address — the composed alias+world upgrade can cost one
cold refill, documented). A stale adoption after a pre-upgrade reseed
persists exactly like main today (a pre-generated reset does not
wall-clock-heal; `/lss reset` is the cure), and the once-ever condition
contains every repeat. Seedless sessions (waiting rooms, seed 0, unreadable)
read/write the bare bucket and never adopt. Skipped entirely when the kill
switch is off — but note the switch is NOT a rollback lever: after adoption,
switch-off sessions read an empty bare bucket (cold refill, LSS-root-only —
the same worst case the v4 cache-format bump accepted).

**Sibling-bucket cap (§9 fold).** Seed-randomizing servers (the
AntiSeedCracker family's per-login modes) would otherwise mint one bucket per
join, killing the warm path silently and growing without bound. At creation
of a new `.world-*` sibling beyond 8 per address component, the oldest
sibling is deleted (in-root deletion, same containment as the reset sweep)
and one WARN names the server as seed-unstable.

Accepted-open (recorded): two same-seed worlds under one address still share
a bucket (the seed is the only world identity a vanilla client gets), and
Xaero's own multiworld detection can split where the seed does not — so the
finer-never-coarser claim is scoped precisely: LSS is never coarser than any
partition DERIVABLE FROM (address, seed); a consumer splitting on other
evidence keeps its main-identical residual. The `unknown`-address bucket
gets the sub-key too (stock Voxy splits its UNKNOWN twin by world id — the
sub-key keeps LSS matching there). The equality with Voxy is modulo Voxy's
truncated-sha256 world id (harmless) and holds over the level key, which
LSS's per-dimension files share. Dropping PR #243's liveLssSession predicate
term is recorded deliberate: a replayed SessionConfig manufactures a "live
session" either way, v2+ has no seed-named deletion for the term to guard,
and a wrong-address replay's stamp pollution now lands in
`(staleaddr).world-(originseed)` — better contained than main's bare-bucket
pollution. Old-seed buckets between resets are swept by reset (§2.4) and
capped (above); otherwise inert stamp files under LSS's own root.

### 2.4 Reset completeness

`LodRequestManager.flushCache` clears, in ONE IO task via the NEW
`ColumnCacheStore.clearForServers(Collection<String>)` (per-member try/catch;
one `runIoAndWait`, not N 30 s waits): for every member of the group (or just
the connect address when no group matches) — the bare bucket AND every
sibling matching the ANCHORED pattern `\.world-[0-9a-f]{16}$` (entry NAMES
under cacheDir only, never resolved paths — inheriting `clearForServer`'s
non-following delete; the store learns the suffix convention, not alias
semantics), with member matching CASE-INSENSITIVE against normalized
spellings so historically capitalized buckets are swept too (§9 fold).
`/lss clearcache` rides the same path. Nothing outside LSS's own cache root
is ever touched; no consumer-store deletion exists on any path (the PR's
seed-named Voxy wipe stays rejected). (So §2.1's "ColumnCacheStore
untouched" is precisely: untouched on the load/save path; the store gains
exactly this one sweep method.)

### 2.5 What this deliberately does not do

No wire change, no server change, no new information to the client (§1.3), no
consumer-store writes or deletions, no seed-as-identity anywhere. A future
consumer-side world identity for the Xaero bridge (per-world map ids) would
slot into the same corroboration seam.

## 3. Design B: the reset storage-override escape hatch (v1 post-fold,
unchanged)

### 3.1 The report

On a declined wipe, `/lss reset` prints (chat + log from ONE pure assembler,
`ResetStorageReport`): Voxy's live root, the derived root, and a
verdict-specific cause. Five verdicts: `MATCHES` / `OVERRIDDEN` (checked and
disagreed — the only "another mod redirected Voxy's storage") / `UNVERIFIABLE`
("could not be verified against", never "does not match") / `NO_INSTANCE`
(plain reset DOES wipe via the fallback derivation — the only verdict carrying
the "run /lss reset instead" hint) / `UNAVAILABLE` (domain unresolved or the
probe threw — plain reset wipes nothing, no hint). `ResetCoordinator.Deps.
voxyReset` returns a small report carrier (outcome + both roots), not the bare
enum. Message-grade derivations contained on BOTH arms.

### 3.2 The force grant

`/lss reset voxy-force` (stage 1): read-only probe; the containment fence runs
READ-ONLY first — an outside-fence live root prints "cannot be wiped even with
force" and arms NOTHING; otherwise print the report + "stage 2 deletes
exactly: <live root>" and arm a one-shot `ForceGrant(normalizedLiveRoot,
armedAtNanos, connectionIdentity)` in an `AtomicReference`.

`/lss reset voxy-force confirm` (stage 2): proceed only when a grant exists,
is under 60 s old, matches the connection (`ClientPacketListener` object
identity, no-session sentinel), and `samePath`-equals the FRESHLY probed live
root; consumed either way; any mismatch re-prompts (re-arming) and deletes
nothing. Direct `confirm` with no grant IS stage 1. Disconnect clears the
grant — but identity + disconnect are best-effort belts; **the stage-2
samePath re-probe is the shown==wiped invariant** (both stages on the main
client thread). Force waives exactly the derived-root comparison; the fence
applies unchanged; the no-session branch discloses the ALL-servers cache
clear verbatim like the plain path. The four-node brigadier subtree is shared
by both loaders, its node→(confirmed, force) mapping sink-pinned, both
loaders' USAGE source-pinned.

## 4. Implementation steps

1. `CacheKeyAliases` + `AliasCorroboration` + `WorldSubKey` (xplat, pure) +
   the two config keys + load validation. Tests: normalize table, group
   selection, the post-fold corroboration table with the voxy-extra fixture,
   the WorldSubKey predicate table (every term + seed-0 + unreadable),
   reserved-namespace rejections (incl. `.world-` entries), config-shape
   edges.
2. `VoxyCompat.observeStorageDirName()` — own two-handle domain, contained,
   own log line. Tests via hook seams incl. reset-domain-dead-probe-alive and
   probe-dead-with-live-ingest.
3. `AccessorBiomeManager` + both loaders' mixin-config entries + the
   SeedAccessorContractTest shape (reflective field pin + both configs).
4. `ClientNetGlue.createRequestManager` + the manager's dimension/cache
   phase: alias latch (set at first build, reset in `onJoin`), LIVE world
   sub-key per build and per dimension entry, adoption move (IO-thread task,
   once-ever condition), sibling cap, diag/log,
   `ColumnCacheStore.clearForServers` + the anchored-glob sweep. Source-pin
   the wiring. Differentials: kill switch off = byte-identical bucket for
   EVERY session shape; post-adoption switch-off = empty bare bucket
   (documented, pinned separately); empty alias config + non-member address =
   byte-identical; a mid-connection rebuild reuses the ALIAS latch but
   re-reads the world sub-key (a changed live seed re-keys — the
   reconfiguration/backend-switch differential); a JOIN resets the latch;
   adoption never fires seedless, never fires with any `.world-*` sibling
   present (the reseed-after-lobby-residue case), never fires switch-off,
   and a queued prior-session save cannot resurrect the moved bare bucket;
   the reserved-tail escape on raw addresses; per-dimension entry-time
   sub-keys (old dim saves under its load-time key).
5. `ResetStorageReport` + five-verdict split + carrier reshaping. Tests: hard
   line expectations per verdict (no assertEquals(f(x), f(x))); verdict ↔
   outcome mapping.
6. `ForceGrant` + coordinator branches + shared subtree + registrations.
   Tests: the full v1 §4.5 list (TOCTOU, expiry, connection change,
   direct-confirm-is-stage-1, consumed-grant double-confirm, vanished
   instance, force+SHUTDOWN_FAILED skip, outside-fence stage 1, shown==armed==
   wiped identity, ordering pins, disclosure text, real-FS containment,
   Brand rendering).
7. Diag `Cache:` line + README paragraphs + CLAUDE.md one-liners with the
   plan pointer. Neither new config key gets a `ClientOptionCatalog` row
   (repo convention: expert kill switches are file-only, and
   `cacheAddressAliases` is structurally unrenderable in the Sodium page).

## 5. Validation

- Full Tier 1 both platforms + Tier 2; NeoForge build; PLUS (§9 fold — a
  cache-identity feature must run the cache-carrying harness legs):
  `warm-rejoin`, `cold-restart-resync`, and one chained `stamp_heal.sh` run.
  The harness stages/collects whole cache ROOTS, never bucket names (verified
  in the §9 review — soak.sh staging and the benchmark clear are
  layout-transparent, and `level-seed=soak-seed-42` makes the sub-key
  deterministic), so these validate adoption + the new layout rather than
  needing harness changes.
- Live, two-entrance rig via `/etc/hosts` (matrix includes one capitalized
  and one port-bearing spelling; assert the diag reason token): warm on name
  A, join name B → no cold refill with group + voxy-extra; fallback WARN
  without. World axis: re-seed the test server's world (fresh `level.dat`
  seed) → diag shows a NEW `world-` sub-key, old bucket untouched, terrain
  re-streams; restore the old world → the old sub-key and its warm stamps
  return (ordering matters: the FIRST plan-jar join must be on the original
  world so adoption lands there — an already-reseeded rig adopts old stamps
  into the new bucket, exactly today's staleness, and the restore leg then
  cold-refills; stage the rig accordingly). Adoption: upgrade path from a
  pre-plan cache dir → one rename, no re-download. Force path: stage 1 on a Flashback-overridden root, confirm
  after switching connections → re-prompt, nothing deleted.

## 6. Backports

Pure client Java + config + command tree + the one accessor mixin (verified
portable across all four lines in the PR #243 review; both mixin configs are
line-identical in shape). The `ClientCommandManager` vs `ClientCommands`
literal-factory token on 1.21.x lines is one token per line.

## 7. Relationship to PR #243

Problem statement, the seed insight (as a sub-key), the pure-Context
predicate, the accessor + its contract-test shape, the one-assembler report,
and the shared subtree all originate in PR #243 by OowhitecatoO — credit in
the changelog. The seed-as-identity mechanism, its corroboration-free
application, and its consumer-store deletion are replaced per §1.2.

## 8. v1 plan review fold (2 Fable, 2026-08-27) — retained

Both reviewers: NEEDS-REWORK scoped to v1 §2.2, converging independently on
the same two MAJORs: (1) the corroboration guard failed OPEN
(Voxy-live-but-unprobeable read as "unobservable → APPLY", and the probe
inherited `initResetDomain`'s all-or-nothing domain incl. the unstable
renderer rung); (2) the comparator was specified against Voxy's `:`→`_` munge
when voxy-extra substitutes the canonical VERBATIM (exact case-sensitive
matching; port-bearing canonicals split voxy-extra's own store and break on
Windows). Both folded into §2.2 above, plus: raw-spelling canonical buckets,
the dropped `:25565` strip (SRV hazard), reserved-namespace validation, the
per-connection latch, `clearForServers` as named new surface, the ARMED Xaero
gate, the five-verdict report split + carrier, the stage-1 fence pre-check,
`connectionIdentity` named with samePath declared the true invariant, the
different-group-shape WARN wording, NeoForge scope honesty, test additions
throughout, and the live-matrix spellings. Accepted-open items recorded in
§2.3/§2.4.

## 9. v2 plan review fold (2 Fable, independent, 2026-08-27)

Both reviewers: NEEDS-REWORK, and for the second round running they converged
independently on the same central defect — the connection-wide LATCH:

- **M-A1/M-B1 (both reviewers): the latch made the world axis COARSER than
  Voxy mid-session**, falsifying the "by construction" equality on the
  headline topology: Voxy re-derives its world id at every Level
  construction (per-respawn seeds are real — CommonPlayerSpawnInfo carries
  the seed per respawn packet, CraftBukkit fills it per destination world),
  while a latched LSS key filed world B's stamps under world A's bucket
  across re-handshaking backend switches AND across dimension switches on
  multi-world servers, permanently on hub topologies. Folded: the alias
  decision alone is latched (reset at JOIN — disconnect never fires on
  play→config reconfiguration, M-B2); the world sub-key derives fresh per
  manager build and per dimension entry from the live level, carrying
  forward only on an unreadable read. PR #243's per-JOIN re-read — which its
  javadoc correctly called load-bearing — is thereby restored and extended.
- **M-A2: adoption re-fired after reseeds**, swallowing lobby/seedless
  residue into each new world's bucket (a v2-introduced hole). Folded: once
  EVER per address component (condition: no `.world-*` sibling of any seed),
  IO-thread execution ordered before the first load, failure = empty seeded
  bucket + retry, source = the key's own address component.
- MINORs folded: sibling-bucket cap for seed-randomizing servers (unbounded
  growth + silent warm-path death); reserved `.world-` tail escaped on RAW
  connect addresses + the reset glob anchored to `\.world-[0-9a-f]{16}$`
  over entry names + case-insensitive member sweep; the finer-never-coarser
  claim scoped to partitions derivable from (address, seed) with the Xaero
  multiworld and same-seed residuals named; the `unknown` bucket gains the
  sub-key (Voxy splits its UNKNOWN twin); kill-switch prose corrected (not a
  rollback lever post-adoption); stale-adoption wording aligned with §1's
  own A2 analysis; soak legs added to §5 with the root-staging analysis
  recorded; §5 re-seed rig ordering; the liveLssSession-drop walk recorded;
  the Voxy-equality claim stated precisely (truncated-hash, level-key);
  composed alias+world upgrade cost documented; no catalog rows.
- Verified by the round (recorded): the store's single-FIFO IO thread is the
  adoption-ordering backbone; `mc.level` is non-null at every real manager
  build; the harness is bucket-layout-transparent; §7's credit list is
  accurate; Design B and all §8 folds survived re-examination untouched.

## 10. Landing strategy: contribution preservation (user direction 2026-08-27)

The implementation lands THROUGH PR #243, keeping OowhitecatoO's contribution
real and visible rather than re-attributed:

1. **Branch from their PR head** (`pr-243`, commits `18cabb10` + `6c9b60c0`,
   author `WhiteCat <whitecatx6@gmail.com>`): their commits stay in the branch
   history byte-for-byte, authorship intact.
2. **Merge main INTO the branch** (never rebase — a rebase rewrites their SHAs
   and committer identity; a merge is the ordinary contributor workflow and
   keeps their commits exactly as pushed). Resolve the ~4-merges-behind drift
   by hand.
3. **Implement this plan as a commit series on top.** The diff from their head
   to the final tree IS the review rework, and the messages say so. Files
   directly derived from their code — the `AccessorBiomeManager` mixin + both
   config entries, `SeedAccessorContractTest`, `SourcePaths.repoFile`, the
   shared `resetSubtree` + `ClientResetSubtreeTest`, the report-assembler
   concept, and `WorldSubKey`'s pure-Context shape (from `WorldSeedKey`) —
   carry `Co-Authored-By: WhiteCat <whitecatx6@gmail.com>` trailers.
4. **Push the finished branch to THEIR fork's PR branch**
   (`--force-with-lease` to
   `https://github.com/OowhitecatoO/lod-server-support.git
   HEAD:seed-cache-key-and-reset` — permitted: `maintainerCanModify` is true).
   PR #243 then shows the whole evolution: their commits first, the merge, the
   rework.
5. **Update the PR description** (maintainer edit) to describe the final
   two-axis shape and the collaboration, comment the rework rationale
   (short, plain, per external-message style — pointing at this plan doc and
   the review rounds), and **merge PR #243 with `--merge`**: the merge commit
   reads "Merge pull request #243 from OowhitecatoO/seed-cache-key-and-reset",
   their commits enter main's history, and the merged-PR credit lands on their
   profile and the repo's contributor graph.
6. Fallback (only if the fork push is refused despite the flag): the same
   branch as an in-repo PR crediting them in title/body/trailers, closing
   #243 with a link — recorded here so the preference order is explicit.

## 11. Implementation fold (1 Fable + 4 Opus panel, 2026-08-27) — as-built corrections

All five reviewers: SHIP-WITH-FIXES; every §2/§3 obligation and both §9 fold
MAJORs verified delivered. Folded (all fixed in the implementation):

- **Cross-dimension unstamp bucket (session MAJOR)**: `onIngestFailure`'s
  cross-dimension `removeAsync` assumed a session-constant bucket — the manager
  now records each dimension's load-time bucket (`dimensionBuckets`) and routes
  late unstamps there, closing the reopened #36 hole on per-world-seed servers.
- **Non-following deletes (FS MAJOR)**: `deleteBucketDir` gained the
  `NOFOLLOW_LINKS` guard (a symlinked bucket is deleted as a LINK) and is now
  the one body behind `clearForServer`/`clearForServers`/`clearAll`.
- **Wiring source-pin (tests MAJOR)**: `TwoAxisWiringContractTest` pins the
  factory's latch/keying/live-context wiring and the gate's two session
  brackets — the §4.4 deliverable that had been skipped.
- **ForceGrant hardening**: the grant's root now travels INTO the ladder
  (`resetVoxy(hooks, force, grantedLiveRoot)`) — force applies only to a live
  root samePath-equal to the granted one, and the no-instance fallback wipe is
  refused under force; the gate clears the grant at JOIN as well as disconnect
  (the reconfiguration gap); the declined-wipe report carries
  `liveRootContained` and offers voxy-force only where stage 1 could arm it;
  `voxyPresent` now means the MOD is installed, not that the ingest bridge
  resolved.
- **Adoption residue guard**: a session that used the bare bucket (a seedless
  lobby leg) never adopts it into a later world's bucket (`allowAdoption`);
  sibling matching (cap + adoption blocker) is case-insensitive like the sweep.
- **Alias validation**: dropped groups claim nothing (no cascade), intra-group
  case variants dedupe instead of dropping the group, and `validate()` no
  longer rewrites the user's field (the load-time re-save would have erased
  dropped groups from the file).
- **Eligibility**: both "unknown" flavors (no ServerData, and ServerData with a
  null ip) are world-axis-eligible via one predicate.
- Naming correction: §3's "ResetStorageReport" shipped as the
  `VoxyStorageOverride` assembler + the `VoxyResetReport`/`VoxyStorageProbe`
  carriers.

Accepted-open, recorded per the panel: a NEW `ClientLevel` with a different
seed under the SAME dimension key (an in-place world regeneration, no respawn
into another key) does not re-derive mid-dimension — Voxy re-partitions there
and LSS heals only at the next dimension change/rejoin (the pre-plan behavior;
closing it needs a per-tick level-identity watch). A cross-session lobby-first
join can still adopt pre-upgrade lobby residue (bounded by once-ever). The
reserved-tail escape applies switch-independently (sweep safety outranks
byte-identity for that pathological spelling). The alias latch samples
corroboration once per session at first build — a Voxy instance that appears
later corroborates only from the next session.
