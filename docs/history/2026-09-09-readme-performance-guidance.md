# Historical README performance guidance — archived 2026-09-09

This is the superseded wording from this line before the project-improvements documentation migration. Its savings estimates and tuning recommendations are historical prose, not current measurements or recommended defaults. Use [current performance guidance](../operations/performance.md).

### Server Performance Tuning

**Turn the LOD store on.** `"lodStore": "on"` is the single biggest CPU performance win available, it caches the preprocessed LOD data to disk with the drawback of **roughly doubling (+70%) the size of your world directory**.

When the LOD store is enabled a backfill task will populate it. Maximum CPU savings are only achieved after the store is populated. Check the progress of the task with `/lsslod store backfill status`. If your world is much larger than the area players commonly visit you can save on disk space by disabling the backfill task with the `"lodStoreBackfill": false` config.

**Use the bandwidth and generation limiters to limit CPU.** LOD Server Support's CPU cost is essentially how many columns per second it serves plus how many chunks it generates, and these configs cap exactly that:

- `mbPerSecondLimitPerPlayer` / `mbPerSecondLimitGlobal` bound the chunk serve rate. They count **uncompressed** bytes on purpose so compression doesn't quietly raise the real ceiling. The actual max network utilization will be approximately 1/8th of these limits.
- `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` bound new chunk generation, by far the most expensive thing LOD Server Support can trigger. On a server exploring fresh terrain this dominates, and lowering it is the single biggest saving. `enableChunkGeneration: false` removes it entirely.
- `maxConcurrentDiskReads` bounds how many LOD disk reads run at once, so LOD traffic can't monopolize disk I/O that vanilla chunk loading needs. The `0` auto default is right for most servers; lower it to `1` or `2` if gameplay chunk loading stutters while LODs stream, raise it if LOD loading feels slow on fast NVMe storage.

**Disable LOD store resweep on Paper.** `"lodStoreResweepSeconds": 0` This will reduce CPU utilization at a slight cost to correctness, on Paper its possible to miss chunk updates so old LODs could be served.
