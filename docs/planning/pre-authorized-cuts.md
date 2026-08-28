# Pre-authorized per-line cuts (V-1/D3)

The consolidated list of feature cuts the BEST-EFFORT support tiers may take without a
new decision entry (the neoforge plan's §6.2 protocol: a cut ON this list needs no
dated decisions-log entry; a cut NOT on it does). Sources: the neoforge plan's
pre-authorized list, the 1.21.1 spike's feature-drop list, and the recorded 1.21.8/
1.20.1 port decisions. Release notes for an affected line must still NAME the tier and
the cut (the Folia-experimental rule).

| Cut | Where it applies | Mechanism / precedent |
|---|---|---|
| Tier 3 (client gametests) | best-effort lines (1.21.1; NeoForge everywhere) | never existed there; the fabric mainline keeps it |
| `useBackgroundReadSplit` / `useSelectiveNbtParse` compiled OFF | old lines where the IOWorker/NBT internals fight | the flags are the rollback; spike pre-authorization |
| Sodium 0.8+ options WALKER absent (`LSSConfigMenu` + the `sodium:config_api_user` entrypoint) | lines with no 0.8+ Sodium artifact (0.7-only: 1.21.10; the frozen 1.21.8) | since sodium-options-page-generations-plan.md the in-game page is NOT cut on those lines — the line-invariant legacy builder renders the same catalog on Sodium 0.6/0.7; only the walker file + entrypoint are dropped (entrypoint ⇔ file, contract-pinned); config-file keys keep working regardless |
| AntiXray crash shim → pass-through | Java-21 lines (ScopedValue is preview) | the 1.21.11 flavor; becomes a one-file swap after V-2/S5 |
| Degraded `/lss reset` ladder | lines whose Voxy build lacks the holder surface | confirm-gated fallback ships; the full ladder needs a resolvable Voxy holder surface (0.2.18's static holder or the 0.2.11/dev instanceof-fallback rung) |
| Far-player RENDER path | NeoForge v1 (no community Voxy build); per-line render reworks may lag | tracker/wire/prefs-carrier bit stay LIVE — the render path is the only cuttable half (the capability bit is never cut). **UN-cut on the 1.21.1 line in v0.14.0** (the NeoForge renderer was implemented — `RenderLevelStageEvent.AFTER_ENTITIES`; only 26.1/26.2 NeoForge remain render-cut) |
| `SOAK_PLATFORM=<line>` abbreviated or skipped | best-effort lines | the stage-N decision precedent; the §5.5-style manual checklist becomes the per-release floor, recorded once |

Never cuttable, for the record: wire compatibility (every jar speaks the same protocol
at full fidelity), the v16/v18/v19 compat rungs (user exception — permanent), the
far-player capability bit (prefs carrier), and any `release_check.py` gate.
