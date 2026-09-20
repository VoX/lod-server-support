# Parent wire/codec validation

Baseline: MC1.21.1 merged tree 1b544494.

The dedicated Astra wire reviewer ended with an automated content-filter error. Parent inspected NativeToV20Translator, V20ToNativeTranslator, WireSectionCursor, WireDialectTracker, VoxelColumnS2CPayload and the corresponding cursor, compatibility, payload and compression tests. This is a focused replacement pass, not a claim that the aborted reviewer completed a full protocol audit.

Checked palette translation/collapse, native long-array prefix and count-short branching, dictionary references and clear-column invariant, raw/compressed retention charge, decode-time dialect tagging, and lifecycle dialect retention. The declared 8 MiB section envelope, unknown identity fallback, unknown codec downstream rejection and dimension-change dialect survival are intentional contracts, not findings.

Executed on Java21: :fabric:test --tests dev.vox.lss.common.wire.* --tests dev.vox.lss.networking.payloads.* --tests *XverLiveCorpusDecodeTest --tests *WireDialectTrackerTest, --max-workers=1. 169 tests, zero failures/errors/skips. Log and detailed result census saved alongside this report.

No confirmed new correctness defect from this focused pass. Palette collapse uses a quadratic distinct-id walk; do not recommend rewriting it absent representative profiling. Cross-line native-shape fidelity is assigned its own Astra review.
