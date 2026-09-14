# Performance diagnosis and tuning

Capture a workload and its outcome before changing settings: number of connected clients, whether terrain already exists, whether the store is warm, and timestamped `/lss diag`, `/lsslod diag` and `/lsslod store status` observations. Separate gameplay responsiveness, client frame time and LOD progress. A client report cannot identify a remote disk, generation or bandwidth bottleneck. Queue presence and a single slow sample are observations, not a diagnosis.

| Setting or mechanism | What it controls |
| --- | --- |
| Server MiB/s limits | Raw bytes before compression, per player and globally. They are not a prediction of wire throughput. |
| Generation concurrency | New generation admissions. Per-player limits cannot exceed the configured global limit; lowering a cap does not cancel work already in flight. |
| Disk concurrency | `maxConcurrentDiskReads=0` selects AUTO from the reader pool and attached store. An explicit value is also bounded by the effective pool. |
| LOD store | Reuses processed columns at the cost of local storage. Actual space and throughput depend on the world and workload. New installs default ON; an upgraded file missing the key remains OFF. |
| Client rate controls | The configured manual rate combines with adaptive controls. Zero manual columns/s means unlimited by that manual setting, not unlimited throughput. |
| Dirty pushes and Paper resweep | Maintain freshness through different paths. Disabling them changes correctness or freshness behavior; retain them during ordinary performance diagnosis. |

Use the [generated settings reference](../reference/settings.md) for exact domains, AUTO meanings, platform applicability and restart requirements. `/lsslod set` lists the existing runtime controls. Change one relevant setting at a time and repeat the same workload; restore it when the result does not support the change. A restart-only edit needs a restart to affect running services. Do not trade away anti-xray, privacy or compatibility behavior as part of a performance preset.

The [pregenerated-world preset](status-and-presets.md) stages server-wide generation OFF for restart and is appropriate only when that behavior is intended. It does not prove terrain exists. The opt-in conservative preset uses the accepted 32/4/1 values described below; defaults remain unchanged.

Maintainers should use the [disposable rig](disposable-rigs.md) and its fixed reference experiments. Keep Java builds and other live workloads outside the measurement slot, retain failed attempts, and compare exact artifacts. The test-only `-Dlss.test.disableStatusCollection=true` switch provides a diagnostic A/B control for collection overhead; it is not an operator tuning recommendation or the preregistered performance baseline. The fixed experiment binds its exact registered baseline: either the original pre-improvement artifacts or a separately qualified, explicitly attributed correctness-corrected reference. A corrected-reference comparison does not measure those correction costs against the defective original; the original failed experiment remains retained. Both arms must contain the same shared correctness fixes. This comparison measures the remaining improvements, not the total cost of the original-to-final changes. A faster LOD completion time alone does not establish acceptable frame or server-tick behavior.

## Accepted 26.2 comparison (V27, 2026-09-14)

The fixed experiment completed three baseline calibration runs and three measured baseline/candidate pairs, with 120 seconds warmup, 600 measurement and 120 drain per run. All 30 calibrated comparisons passed under the unchanged median-plus-two-of-three rule. This does not mean every pair met every bound: third-pair server tick-delay p99 increased **0.216949 ms**, above its **0.1919346 ms** bound. The aggregate entry reports that same server observation, not an independent second failure.

The selected opt-in server preset is radius **32 chunks**, global generation concurrency **4**, and per-player concurrency **1**. Defaults stay unchanged; use the [preview/apply/undo workflow](status-and-presets.md). Measurements cover four Fabric clients and a Folia server on Minecraft 26.2 only. Other supported lines receive the same guidance, not a claim of measured parity. See the [public result summary](performance-v27-summary.json) for exact measured arm trees and product hashes.

Both arms used fixed client 1500M and server 2G heaps with AlwaysPreTouch, EDF 4, guest CPU placement, client ActiveProcessorCount=16, and 30 FPS/minimized policy. RSS is resident process memory including committed/touched heap and native memory, not live-heap occupancy. Whole-client frame cadence includes pacing and possible iconified throttling: no 33 ms subtraction or isolated Xaero pump CPU interpretation is valid. The earlier six functional prerequisites retain their own older placement scope.

The corrected reference shares the correctness fixes with the candidate, so this comparison excludes those shared correction costs. Xaero observations describe completed writes over actual roughly one-second intervals alongside frame metrics; they add no threshold or per-pump timing claim. Final integrated all-five builds, exact-artifact native checks, eight human assessments and six fresh final Astra reviews remain pending.
