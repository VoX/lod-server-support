# Visual review follow-up — 2026-09-20

The user's image review identified two distinct map defects and an inadequate seated-player capture. Both map causes are established for the recorded Minecraft 1.21.1 / Xaero 1.45.0 profiles. No shipping map correction has been applied. The previous automated-green results establish their recorded assertions, not visual acceptance. The earlier 54-case progress index remains a dated historical selection; these findings supersede its “only sign-off remains” interpretation.

## Gold ramp: missing shading at a region boundary

The missing dark stripe is at world X=512, a 512-block Xaero region boundary. Fixture terrain height resets from 71 to 64 every eight blocks. At this boundary the rebuilt map buffer records diagonal slope +1; the equivalent reset at X=520 records -7. Native texture bytes are correspondingly brighter at X=512. Terrain heights are correct: this is missing shading, not absent terrain data.

The actual Xaero Fabric 1.45.0 classfiles show `MapTileChunk.updateBuffers` requesting north, northwest and west neighbors with cross-region lookup disabled. `getNeighbourTileChunk` returns null across that boundary; `MapBlock.fixHeightType` substitutes a nearby slope when the preceding height is unavailable. Retained bridge and later native rebuilds share the +1 boundary result in all four original modern/legacy × Fabric/Neo captures. Some intermediate native scan records show zero before buffer rebuilding; the conclusion concerns the rebuilt texture, not every scan event.

The bridge deliberately preserves Xaero's region-local neighbor/locking behavior. The existing native-parity checker passes because the bridge matches the native result, including this limitation. It does not establish seam-free shading. An actual correction needs a separate boundary-shading design and an independent expected-slope assertion; simply loading foreign regions from the existing locked path would violate the current ownership discipline.

## Eight chunks: a gap between acquisition and native map coverage

With player chunk (16,16), the eight affected world chunks are (12,13), (12,19), (13,12), (13,20), (19,12), (19,20), (20,13), and (20,19): offsets (±4,±3) and (±3,±4). They project to the black squares in both Fabric images and the Neo legacy image. One expected cell had already filled in the original Neo modern capture, so that image has seven black centers.

`SpiralScanner` and `RegionScanner` omit chunks inside vanilla's rendered footprint. Xaero's native writer requires the center chunk and all eight adjacent chunks to be FULL chunks, excluding `EmptyLevelChunk` placeholders. At effective view distance four, exactly eight excluded chunks lack a required neighbor. LSS does not request them and Xaero cannot yet map them. The bridge's loaded-edge rule works for an offered tile, but these tiles never reach that offer.

A fresh diagnostic run, `20260920T152851Z-c6ffa41a10fc`, measured the real client's loaded chunk grid without forcing loads. All four samples partitioned 169 local cells into 109 FULL and 60 missing chunks, exactly matching vanilla's buffered send footprint. The difference between LSS's exclusion and complete native 3×3 coverage was exactly those eight chunks. Their projected PNG centers were all opaque black. Samples were stable for 1.514 seconds; the last sample preceded the capture by 257 ms, rather than being literally the same frame. The diagnostic run cleaned up successfully and is not a visual acceptance record.

For a=max(0,|dx|−1), b=max(0,|dz|−1), the audited 1.21.1 render exclusion is E(r): a²+b²<r². Vanilla buffered tracking is S(r): min(a,b)²+max(0,max(a,b)−1)²<r². At r=4, S(4) happens to equal E(5) on integer positions; this is not a general radius-plus-one identity. The diagnostic establishes the actual arrival premise for this run. It strongly explains the reported first-spawn pattern, but does not prove every transient hole on every world has this cause.

### Focused acquisition correction to implement

1. Add an optional, bounded Xaero-specific supplemental candidate source at the shared scanner dispatch. Keep generic/Voxy render exclusion and both scanners' prefix/quadtree optimizations unchanged; changing only the per-cell predicate would miss skipped whole rings.
2. Derive eligibility from the current world's real FULL-chunk 3×3 coverage (excluding `EmptyLevelChunk` placeholders), an active receiving manager and a live enabled Xaero acquisition session. Respect bridge settings, both-writes-off state, retirement, dimension/session replacement and effective LOD bounds. Do not approximate eligibility with a larger radius.
3. Compose supplemental and ordinary candidates within one existing pressure/wire budget and one want-set/tracker update. Preserve cadence, receipt ownership, failed-send behavior, truthful truncation and convergence without a heartbeat. A supplement consuming the budget must not confirm unvisited far rings; cap-one tests must prove eventual progress in both arms.
4. Test the exact eight-cell reproduction under both scan arms, a fully surrounded negative control, disabled/absent bridge behavior, preconfirmed prefixes, movement/distance changes and session reset. Native modern/legacy captures must show those cells filled while the player remains stationary.
5. Treat retrospective replay of already SATISFIED columns as separate design work. The narrow first-spawn fix must not fabricate ingest failures or claim to heal every column delivered while the bridge was disabled.

## Seated players: capture repair

The original images were too distant to assess and were captured after the intentional renderer fault. That fault correctly latches the affected vehicle type and produces standing fallback; the old gallery incorrectly described this image as the healthy seated pose. The original five semantic assertions remain useful but do not make the screenshot suitable for seated visual review.

The fixture and controller now use a real spyglass view, wait for both distant passenger proxies to complete healthy dispatcher calls, capture `seated-healthy.png`, and only then release a run-bound gate that arms the injected failure. `seated-recovery.png` separately records the expected standing fallback. Both players remain beyond 128 blocks and absent from the native entity list. The primary `visual_render` binding is the healthy image; the recovery image is supplemental. Captures are original PNGs, without image editing or synthetic enlargement.

This repair and the optional map coverage probe are present on all five maintained support lines. Their live fixtures deliberately compile against the 1.21.1 scenario target. The focused Python suite passed 27 tests per line (135 total, no skips). The map probe and initial seated fixture were freshly built with unchanged shipping inputs. The first new seated attempt, `20260920T153910Z-f61822c4648b`, failed before capture: its new combined distance/native-presence check permanently latched while scene placement was still underway. The old combined log does not establish which subpredicate failed. Its failed result and complete cleanup are retained. A Probe-only successor waits for valid current-frame readiness before the first healthy observation, then keeps the same predicate terminal afterward. The successor fixture compiled successfully into Fabric-remapped and NeoForge-named test-only JARs, again with unchanged shipping inputs.

## Acceptance and evidence limits

No map image is accepted by this investigation. No automated check or agent review supplies the user's visual disposition. Full evidence integration remains open, along with the separate Windows usability/resource-coexistence observation. V27 and the original shipping builds retain their recorded scope; fixture/tool-only changes do not justify repeating the performance experiment.

## Replacement native captures

- **Fabric:** `20260920T154926Z-a7e51c16390b`. Both healthy and recovery native PNGs were captured; all five same-frame semantic assertions passed, cleanup completed, and visual review remains pending. Healthy PNG SHA256 `05a8a2825c6c5e060de6248696f811a01035ffe5fef48f6dd18c56c8bb976267`.
- **NeoForge:** `20260920T155051Z-36f0d11707ae`. Both healthy and recovery native PNGs were captured; all five same-frame semantic assertions passed, cleanup completed, and visual review remains pending. Healthy PNG SHA256 `9d726eafc402ea81a076eaa262e8b778190642787d64d4e2ac7e2bec30ddc9d0`.

The owned gallery now presents the closer healthy images as cases 5 and 6, with separate recovery images and preserved original captures. An Astra reviewer independently checked the raw map geometry and shading evidence and the setup-readiness correction; root reviewed the capture implementation. A second Astra review verified both terminal captures, their ordering and exact bindings, the unchanged five assertions, and complete owned cleanup. Evidence hashes, fixture receipts and exact capture identities are retained in the [follow-up index](visual-review-followup-2026-09-20.json). The original failed capture is not replaced with a pass.
