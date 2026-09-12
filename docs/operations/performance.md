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

The [pregenerated-world preset](status-and-presets.md) stages server-wide generation OFF for restart and is appropriate only when that behavior is intended. It does not prove terrain exists. The conservative numeric preset is unavailable until the required reference measurements justify its values. This guide makes no throughput, storage-saving or regression claim for the current candidate.

Maintainers should use the [disposable rig](disposable-rigs.md) and its fixed reference experiments. Keep Java builds and other live workloads outside the measurement slot, retain failed attempts, and compare exact artifacts. The test-only `-Dlss.test.disableStatusCollection=true` switch provides a diagnostic A/B control for collection overhead; it is not an operator tuning recommendation or the preregistered performance baseline. The fixed experiment binds its exact registered baseline: either the original pre-improvement artifacts or a separately qualified, explicitly attributed correctness-corrected reference. A corrected-reference comparison does not measure those correction costs against the defective original; the original failed experiment remains retained. Both arms must contain the same shared correctness fixes. This comparison measures the remaining improvements, not the total cost of the original-to-final changes. A faster LOD completion time alone does not establish acceptable frame or server-tick behavior.
