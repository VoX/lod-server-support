# WI-14 current operational documentation

Docs-only commits completed:

| Line | Commit |
| --- | --- |
| 1.21.1 | 8504568d |
| 1.21.10 | 592de067 |
| 1.21.11 | c35213cd |
| 26.1 | 1d691465 |
| 26.2 | caded5f0 |

Inspected each line's neoforge/build.gradle, scripts/release_check.py, .github/line.env and NeoForge FarPlayerRenderer source. All five builds nest sqlite-jdbc and zstd-jni as stock jarJar libraries; release checks reject both flat class prefixes and flat native entries. Shipping flags are true on 1.21.1/26.1/26.2, false on 1.21.10/1.21.11. NeoForge rendering is live only on 1.21.1.

Added a current loader/artifact surface section without renumbering the canonical per-version table. Added current-reference pointers in CLAUDE and the NeoForge support plan while preserving dated spike/port records. Updated README installation guidance to distinguish shipped server platforms, matching client loaders, Voxy or enabled Xaero consumption, and far-player rendering. Corrected its unconditional NeoForge alias-never-corroborates claim to the actual observed-path/corroboration rule. Removed 26.2 CLAUDE's present-tense no-Voxy-route explanation for nonshipping lines; the checked shipping flag remains the stated fact.

Copied the existing 1.21.1 live-profile guide and JSON inventory verbatim to the four sibling trees, authorized by root, so all new relative links resolve. These remain candidate-profile evidence, not live integration certification. No runtime/mod changes, no source changes, no Gradle. All edited doc links checked and git diff --check passed. Root's implementation ledger was not edited.
