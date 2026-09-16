# Fresh final Astra review 3/6 — Xaero and client lifecycle

Date: 2026-09-14. Reviewer: `/root/final_astra03_xaero`. Read-only production/tooling review; no child agents, Java, Gradle, native launches, Windows foreground operations, or maintained-file edits.

**Result: no actionable production-code or maintained-checker findings in the reviewed scope.** No blocker or credible new regression was identified. This completes the independent code review, not final native or human acceptance. The coordinator must request the final-evidence supplement described below before closing acceptance.

## Exact scope and identities

Read assignment 3, the primary and all four sibling `CLAUDE.md` guides, the implementation plan (including P5 ownership/extraction and final acceptance amendments), the ownership matrix, relevant prior behavior pins, and actual baseline-to-final changes. Worktrees are under `/home/vox/projects/lss-improvements/`.

| Line | Original baseline | Reviewed HEAD |
| --- | --- | --- |
| 1.21.1 | `48268c7d7e8740a93fbc6c7cf60fb36f7b75d690` | `0ae20c5f5f41e9482f865b5da7faf6a1d16bfacd` |
| 1.21.10 | `948b0bf09dd852d357176577d7ccacd589aadb7d` | `8a6457bd4250b495d523f9382ff3f73a1c5e7353` |
| 1.21.11 | `8ac799a901e7eb99cb9403d512b790a0b6576190` | `b8828f5ad550c73e26de158b3c56c877cc6e5d8a` |
| 26.1 | `1e7c9a9d3507c3ba90ac511ba6b62995b8796ab2` | `f8f31bb68432db75b32908c83890587cc0ee0dd6` |
| 26.2 | `98b67abc82283417ad288cbb0cca2f4fb3343425` | `6f7081101b4fbbdb946b6160618bc71cd8d20b44` |

These match the actual frozen commits in `accepted-performance-v27-20260914/docs-and-refs-integration.json`, not merely a source-ref document's recorded comparison snapshots. The primary checkout contains untracked historical evidence and a modified map-fixture document; their existence was observed and was not interpreted as current acceptance.

Reviewed production: `XaeroMapCompat`, `XaeroSession`, `XaeroBindings`, `XaeroAcquisitionQueue`, `XaeroTileWriter`, `XaeroRebuildScheduler`, retained `XaeroTileExtractor` adaptations; `ClientNetGlue`, `ClientSessionGate`, the changed `ClientColumnProcessor` ownership check; relevant loader/bootstrap, option-catalog and renderer wiring. Settings/preset semantics beyond lifecycle reconciliation and full diagnostics/privacy coverage belong to the other assigned reviews.

## Code evidence and preserved contracts

- Independently extracted and compared method bodies from each line's **actual original `XaeroMapCompat.java`** to the final five extracted owners. On **each of five lines**, 107 parsed baseline methods/constructors produced **106 matching bodies** after comments, whitespace, extraction routing, class renames and explicit record accessors were normalized. The sole body difference is `queuedGauge = queue.size()` in `XaeroAcquisitionQueue.updateOccupancyLocked`. This independently corroborates, rather than simply trusting, the existing extraction-audit document. The lightweight parser is supplemental structural evidence, not a proof of Java semantics; initialization, call ownership and sensitive paths were also read directly.
- Bindings, acquisition queue, tile writer, rebuild scheduler and public facade are byte-identical across all five reviewed trees. The only cross-line `XaeroSession` difference is the consumer's 1.21.1 `getMinBuildHeight()/getMaxBuildHeight()` versus sibling `getMinY()/getMaxY()+1`; the exclusive upper bound is preserved. Extractor light-opacity calls preserve the existing 1.21.1 two-argument, 1.21.10/1.21.11 zero-argument, and 26.x dampening surfaces. The extractor itself has **no baseline-to-final diff on any line**.
- Acquisition origins retain generation and the captured ingest handle; CAS controls release. Queue and debt membership remain separately protected. Retirement retains acquisition → queue/debt order; overflow reporting and native deferred probes remain outside their forbidden internal monitors. Replaced entries preserve tile/entry comparisons, and old-generation checks prevent detached debt from republishing membership/gauges. `queuedGauge` is updated in the same locked mutation helper as occupancy and bytes.
- OFF retires unresolved acquisition and reports while preserving committed native updates. Disconnect retires the thread-safe half immediately and settles native objects on the main thread. Same-dimension replacement retains origin generation/receipt rejection and native processor/world-ID guards. No flush was added during disconnect; the previously documented loss of recent unrebuilt textures on native-world destruction remains an intentional baseline tradeoff, not a new finding.
- Writer admission retains region write-pause/resting/load/PBO gates, prepared-pixel sequencing, set-never-clear `beingWritten`, and the prohibition on `setToUpdateBuffers`. Boundary ownership still tests the loaded chunk's eight neighbors, and slope invalidations remain region-local with rebuild capacity reserved before pixel mutation.
- Rebuilds preserve native processor/world/tile identity, hard/soft membership bounds, frame/tick allowance sharing, first-progress exemption, bounded distinct-region probe floor, keep-visited behavior, and failure containment. No new cadence, executor, budget, or native scheduling policy was introduced by the extraction.
- `ClientColumnProcessor` now reports a receipt taken by an obsolete worker after the check/poll race, and reports an active receipt rejected at the final dispatch guard. The existing delivery owner and final release remain in charge. Read the deterministic `teardownBetweenEpochCheckAndPollCannotAcceptANewSessionColumn` test, which forces actual teardown/replacement admission inside the queue poll and asserts no dispatch, one failure, forgotten stamp and zero queue/bytes.
- All ten Fabric/NeoForge `FarPlayerRenderer` files have **no baseline-to-final changes**. Fabric remains available on every line. Primary 1.21.1 NeoForge remains available; all four sibling NeoForge renderer stubs remain unavailable. Primary modern/legacy Sodium paths continue through the existing catalog setter/SaveHook lifecycle route; the metadata extension did not replace those setters. Both loader event routes continue to the common tick/frame/disconnect facade.

Relevant existing tests read before assessing tradeoffs: `XaeroMapCompatTest`, `XaeroAcquisitionLifecycleTest`, the wiring/extractor pins, `ClientSessionGateTest`, and relevant `ClientColumnProcessorTest` cases. Particularly examined OFF retained rebuilds; same-dimension/old-receipt replacement; exactly-once replacement/eviction release; off-thread retirement during owed probing; native boundary/shading/save gates; frame allowance, cap and failure controls. No deliberate pinned decision was relabeled as a defect.

## Maintained fixture and checker review

- **WI5:** examined the actual catalog setter/SaveHook driver and held real prepared-tile callback. Its premise requires a live receipt and positive native rebuild work; actual abrupt transport closure is distinct from an ordinary disconnect packet. Replacement requires a new native level, connection and manager with the same dimension, then observes the old callback's return/closed origin/absent tile. Ordered checker requirements include native retirement and OFF rebuild preservation.
- **WI6:** examined exact participant bindings and acceptance checker. It requires an actual accepted baseline roster/update, bounded false-return retries for the same held epoch, unaffected participant progress, replacement roster/update acceptance, owner-context debt drain and a subsequent no-obsolete-clear observation interval. The proof expressly describes adapter denial, not physical Netty saturation.
- **Map:** examined raw bridge/native scan/texture/save observations, target admission, native writer scan instrumentation, viewport identity, closed-stream validation and stop/close checks. The checker compares target pixels/slopes and the target RGBA subrectangle against a later actual native scan; unrelated pixels in a 4×4 tile group cannot falsely invalidate equality. Save overlap needs a real paused native save, edited target deferral, no successful commit during its interval, and later recovery. It retains raw evidence and actual v20 negotiation. Human screenshot acceptance remains separate.
- **Seated:** actual draw premise precedes the injected dispatcher fault; subsequent independent proxy and tag work must finish in that same frame with restored sentinel/matrices and no crash latch. Exact owned native subject identities and their real consumer/handshake logs are checked.
- **Elytra:** strict v2 recomputation binds exact candidate/fixture and independent target profile, six ordered native/proxy phases, real input/action journal, native server predicate receipts, movement, completed proxy submissions absent from vanilla tracking, camera framing and screenshot bytes. It does not claim Voxy terrain evidence or human visual acceptance.
- Inspected common proof/review handling: missing human review can become `awaiting-review` only after runtime semantics pass; acceptance requires an identified user's disposition and user-message provenance for the exact run/profile/artifact, with frozen semantic and finalized-proof hashes. Automated semantic success cannot manufacture a visual disposition.

Bounded offline command executed from `1.21.1/tools/rig`:

```sh
python3 -B -m unittest test_receive_lifecycle test_receive_report test_receive_run test_send_admission test_seated_draw test_seated_run test_seated_runtime test_xaero_map test_xaero_map_native_fix test_xaero_map_shutdown test_elytra_strict test_elytra_run test_elytra_camera
```

Observed result: **112 tests, 0 failures/errors, 4.877 seconds, exit 0**. These are synthetic/unit controls of maintained checker behavior, not actual Minecraft/native acceptance. A first invocation's completion metadata was not retained by the orchestration output wrapper; the above bounded invocation was repeated once specifically to capture the complete test result. No Java/native process was launched by this review.

## Evidence limits and required supplement

The files named `xaero-map-final-batch.json`, `receive-lifecycle-final-batch.json`, `send-admission-final-batch.json`, `seated-draw-final-batch.json`, and `elytra-final-batch.json` currently inspected in the primary worktree refer to **September 10 historical runs**. Their names or prior pass fields do not establish final integrated candidate acceptance. Map/seated/Elytra records also retain explicit pending visual scope. They were used to inspect evidence structure/history only.

The coordinator reports final integrated full gates passing for 1.21.1/1.21.10/1.21.11, with later lines completing in sequence. Their named evidence directories exist; this review did not independently recount all platform XML or certify the overall build gate. No claim is made here that the pending final 52 native rows or eight human assessments have passed.

Read the accepted V27 public comparison and its stated limits: it covers the exact corrected reference/candidate, four Fabric clients with Folia 26.2, fixed 30 FPS/minimized policy and whole-frame cadence. It is **not isolated Xaero pump CPU measurement**, cannot subtract a presumed 33 ms pacing cost, and does not independently certify sibling performance. The stated aggregate rule and disclosed third-pair server-p99 bound exceedance are not concealed by this review.

Before final acceptance, supply actual final run/manifest/proof/validation identities for the required WI5 four primary profiles, applicable WI6 rows, four primary map rows, seated loader rows and the two Elytra lines, plus all eight real human dispositions and their exact screenshot/run bindings. Recompute maintained checkers over retained raw evidence and verify cleanup, artifact/fixture/profile/settings/checker identity and any changed source since the reviewed heads. Preserve the optional rejected native Voxy pairing and intentional sibling renderer stubs as separate applicability facts. This is the requested evidence follow-up, not a code finding or permission request.

**Disposition:** code review complete, no findings; final native/human evidence review pending coordinator follow-up.
