# C2ME runtime gametest fixture diagnosis and correction

Reviewed the real 1.21.11 C2ME alpha.0.18 remapped classes read-only with Java 21 javap. Production unchanged. Initial evidence: `c2me-12111-regular-map-fourth.log`, 77 tests / 2 required failures. Runtime profile configuration uses replacement chunk IO, async serialization, and the new chunk scheduler; optional Java 25 native module and dependent DFC omitted by the documented dev runtime helper.

## Background disk read

`SerializerParityGameTests.backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn`: both actual reads return nonempty content before the assertion fails on raw_serves=0. The runtime explicitly logs missing vanilla IOWorker executor and adaptive fallback. `ChunkDiskReader.backgroundReaderOrFallback` deliberately handles this replacement IO shape, engages the adaptive throttle, and reads through chunkMap.read. The fixture's unconditional raw-split liveness requirement was incompatible with that supported fallback.

Correction: only the actual C2ME-loaded profile selects a branch requiring an enabled adaptive throttle, its diagnostics receipt, zero raw serves, and no falsely advertised split path. Vanilla retains raw_serves>0 and bg-split assertions. Both retain exact foreground/background byte parity and legitimate content result assertions. These explicit C2ME profiles retain IO replacement enabled; an arbitrary C2ME configuration with IO replacement disabled is not the expected profile of this branch.

## Proto-save exclusion positive control

`ServiceLifecycleGameTests.protoChunkSavesAreExcludedFromDirtyMarking` failed its positive control at tick 0. It previously acquired a chunk, edited it, acquired a proto chunk, and saved immediately, without checking holder readiness. C2ME's `NewChunkHolderVanillaInterface.wasAccessibleSinceLastSave` checks actual SERVER_ACCESSIBLE status; `isReadyForSaving` checks its operation future. Its `getFullStatus` instead derives TARGET status, so that getter is not used as the readiness proof. Vanilla `ChunkMap.saveAllChunks` selects visible holders with the accessibility predicate before saving.

No missing hook established: C2ME's loaded save route retains synchronous SerializableChunkData.copyOf, and its ReadFromDisk unload route calls copyOf as well. The other gametest's delayed real edit/save/dirty positive control passed in the same 75 passing tests. The precise failed tick-0 holder state was not captured; readiness is the source-supported candidate, not a claimed observed async-save trace.

Correction: GameTestSequence bounds the wait at 300 ticks, allows two ticks for holder-map promotion, and checks actual holder wasAccessibleSinceLastSave AND isReadyForSaving before mutation. A single thenExecute performs edit → drain → real level.save → real proto copyOf → drain, with the original positive and negative position assertions. No retrying edits or eventual-dirty acceptance. Explicit proto copyOf ensures the negative hook actually runs even if saveAll omitted a generation-stage holder; the positive still requires the real level save route. On 1.21.1 the direct snapshot call must use its ChunkSerializer.write seam.

Bytecode working evidence: `/tmp/lss-c2me018-review/ChunkMap.javap`, `NewChunkHolderVanillaInterface.javap`, `ServerAccessible.javap`, `readfromdisk.javap`, `serializer-0.javap`. Runtime validation and commit map will be appended after execution.

## Actual validation and ports

| Line / profile | Loaded C2ME | Runtime result | Log |
| --- | --- | --- | --- |
| 1.21.11 regular-map | 0.4.0-alpha.0.18+1.21.11 | 77/77 required pass; 7.007s execution, Gradle16s | c2me-12111-regular-map-fixture-first.log |
| 1.21.11 benchmark | 0.4.0-alpha.0.26+1.21.11 | 77/77 required pass; 7.874s execution, Gradle16s | c2me-12111-benchmark-fixture-first.log |
| 1.21.11 vanilla control | absent | 77/77 required pass; 25.52s execution, Gradle36s | c2me-12111-vanilla-fixture-control.log |
| 1.21.10 benchmark | 0.3.6+alpha.0.11+1.21.10 | 77/77 required pass; 7.221s execution, Gradle20s | c2me-12110-fixture-first.log |
| 1.21.1 benchmark | 0.4.0-alpha.0.27+1.21.1 | 74/75 required pass; both corrected fixtures pass, separate dedup content assertion fails; 7.797s execution | c2me-1211-fixture-second.log |

1.21.1 first attempt stopped at compile: the method port initially used markUnsaved instead of this line's setUnsaved(true). Corrected before the runtime above; log c2me-1211-fixture-first.log retained. Its protected holder lookup uses a narrowly scoped TestPositions.saveHolder reflection seam in the named gametest JVM, with loud failure on lookup error; no production widening. Its negative snapshot calls ChunkSerializer.write; the native chunk key comes from TestPositions.mcChunkKey. 1.21.10 retains Gt assertion wrappers, 26.1 uses ChunkPos.pack and record accessors, 26.2 retains TestPositions wrappers/key seam. The holder read is of the updating map and the two-tick allowance permits promotion; the positive one-shot save assertion still proves actual selection from the visible save set. 26.1 and 26.2 port runtime validation belongs to the parent follow-up gates.

Scoped commits: 1.21.11 f683bf56; 1.21.10 28899adb; 1.21.1 5887c45a; 26.1 7caf3303; 26.2 9638042b. Only gametest files touched, plus 1.21.1 TestPositions helper. Existing unrelated ledger/report edits preserved. All diff checks clean; Gradle handed back to parent after 1.21.10 completed.

### Separate unresolved 1.21.1 C2ME failure

TwoPlayerGameTests.overlappingRequestsFromTwoPlayersDedupeDiskReadsAndBothConverge fails `all three deduped reads must resolve with content`. Both players already satisfy three sends and exactly three submitted reads. This is a content-success counter assertion, NOT the known wall-clock wait timeout. The fixture requests three loaded chunks, removes tickets at tick4, waits for getChunkNow==null, invokes level.save, then queues ingress. C2ME asynchronous unloading may expose absence before persistence completes; actual per-read not-found/error counts are not printed, so this mechanism remains a hypothesis. Do not waive or broaden the content assertion. Next bounded probe should print disk diagnostics and prove all three FULL disk records before ingress; retain exactly three submitted/successful reads and two-player convergence. No production change or rerun was made for that separate failure in this subtask.

## Follow-up: prove 1.21.1 disk fixtures before ingress

The diagnostics-only run (`c2me-1211-dedup-diagnostics.log`) passed dedup but failed `ServiceLifecycleGameTests.probeBudgetPushesTrailingLoadedRequestsToDiskWithoutStarvation`; 74/75. That test loaded two chunks and immediately invoked level.save at tick0, then forced those positions through the disk fallback. The dedup first failure's success/not-found/error partition therefore remains uncaptured; the new diagnostics are retained permanently. These two initial failures are not claimed to have uniquely identified the same timing mechanism.

A narrowly scoped `SavedColumnFixture` now establishes what both tests require before ingress: while their original tickets remain held, wait for actual holder accessibility/save readiness, invoke one flush-save, and issue one bounded batch of 2/3 chunkMap.read futures. Each returned NBT must be FULL and contain the superflat grass_block block-state palette entry; a nonempty light-only sections list does not suffice. No extra ticket, executor, worker thread, service-reader request, repeated save, or read retry is introduced. This setup checks native NBT content; it does not claim an independent validation of the LSS serializer. The original measured content/read-count assertions remain responsible for that leg.

Dedup releases its tickets only after this proof, then admits the same two batches after unloading and retains exactly 3 submitted and 3 successful measured disk reads. Probe-budget keeps the pair held, admits its unchanged 514-entry batch only after proof, and retains exactly 2 submitted reads, zero in-memory serves, 514 routed requests, and free slots at completion. Reader success/not-found/errors/all-air/completed diagnostics distinguish future failures. The fixture exists only on 1.21.1, the line on which the premises failed; no speculative sibling port was made.

Post-change runtime controls: actual C2ME 0.4.0-alpha.0.27+1.21.1 passed all 75 required tests in 6.857s (Gradle 15s), log `c2me-1211-persisted-premise-first.log`. Default vanilla passed all 75 in 31.35s, log `c2me-1211-persisted-premise-vanilla.log`. One green each is evidence that the stronger premises and unchanged measured assertions compose; it is not retrospective proof of the uncaptured first failure's outcome partition.

Final follow-up control: 26.1 default Tier 2 passed all 76 required tests in 25.49s (Gradle 36s), `final-261-fixture-tier2.log`, exercising its previously ported raw/save-hook corrections. The 1.21.1 vanilla control took Gradle 41s. The Gradle slot was handed back after all commands completed. Only the two parent-identified Gradle 9.5.1 daemons (713979, Java 21; 771980, Java 25) were terminated after verifying their command identity and latest IDLE transition. Regular server 489815 was left running.
