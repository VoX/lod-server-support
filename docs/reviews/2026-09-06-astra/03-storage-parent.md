# Storage review — parent-validated partial findings

Baseline: MC1.21.1 merged tree 1b544494. Astra storage reviewer stopped with an automated content-filter error before its full report. Do not count this as a completed full storage audit. It supplied the following concrete candidate and executable probe before the error; parent independently inspected and executed it.

## ST-01 — P1: interrupted mask-policy invalidation records completion over surviving rows

`common/src/main/java/dev/vox/lss/common/store/SqliteLodStore.java:2382–2389`, with `dropDimensionRows` at 2597.

A mask fingerprint change requires deleting the dimension's cached rows before accepting the new fingerprint. `dropDimensionRows` observes shutdown between batches and returns a partial count. The mask-drift caller unconditionally updates and commits the new fingerprint. On the next startup it therefore skips the required policy-change invalidation; unchanged region headers can let old-policy rows serve.

Deterministic external JUnit `ReviewMaskShutdownTest` opens an old-policy store with a row, gates the subsequent changed-policy startup in its injected region resolver, calls actual shutdown (interrupt releases the gate), then normally reopens under the new policy. Expected empty store instead returns the original row. Parent ran it on Java21 through `probe.init.gradle`: one test, RED at the final assertNull (line60), not a timeout or setup failure. Evidence: mask-shutdown-probe.log and mask-shutdown-probe.xml. Production source unchanged.

Fix: expose completed/interrupted status from dimension deletion (or throw an interruption before fingerprint commit), and persist the new fingerprint only after full deletion. Preserve bounded batches, shutdown responsiveness, reader guards and admin-drop semantics. Regression must cover interruption before first batch and between batches, normal completion, and reopening under the changed policy.

Cross-line scope: the entire SqliteLodStore.java file has identical SHA-256 prefix 95150f292f9e1421 across all five lines; fix all five. Test executed only on1.21.1.

## ST-02 — P2: background migration certifies legacy rows without checking their stored checksums

`SqliteLodStore.java:1262–1274` selects only position, size and blob; `:1326–1335` decompresses/translates and writes fresh content/frame checksums. Normal legacy reads verify the original checksums, but migration never reads them. A legacy row with a mismatched checksum can therefore become a valid v20 row instead of being discarded as corrupt. The existing migration corruption test exercises decompression failure, which does not cover a decodable row with failed integrity.

Parent independently inspected this lead and built external `ReviewMigrationChecksumTest` using the existing schema-3 test fixture. It changes one legacy content checksum and verifies two paths: a control normal read correctly misses (GREEN); background migration completes and the same row then serves as wirefmt20 (expected-null assertion RED, line32). No production modifications or real server data involved. Evidence: migration-checksum-probe.log and XML. The injected translator is the store's existing opaque-body test seam, so this proves the migration integrity-policy discrepancy, not MC palette translation fidelity.

Fix: include legacy content/frame checksums in migration row reads and apply the existing wirefmt19 FNV integrity checks before translating/re-hashing. Preserve all-air special treatment, bounded usize, transactional watermark/progress, row-level anomaly deletion and retry semantics. Add decodable checksum-mismatch cases and a valid-row control; preserve existing corruption, all-air and resumable-migration tests. Same identical source on all five lines.
