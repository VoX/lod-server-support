# Status, local diagnostics and settings presets

`/lss status` opens a client screen without Sodium. Sodium's options screens also
have a **LOD status** entry. The screen can toggle reception through the existing
owner apply path; **More** cycles text pages on small screens. VSS uses `/vss` for these local commands. `/lss diag`
retains the detailed troubleshooting counters and also describes OFF, dormant,
negotiation and missing-consumer states without creating a request manager.

Status collects on the client tick at most twice per second, including the detailed
CLI counters. `-Dlss.test.disableStatusCollection=true` disables collection for a separate
diagnostic A/B comparison; it is a test-only JVM switch, not a user setting.
The accepted V27 comparison uses a qualified corrected reference sharing the candidate’s correctness fixes; see [performance scope](performance.md). A disconnect or a
replacement world invalidates its cached data. Progress on the new screen counts
from the first status sample in the current world; existing detailed diagnostics
retain their established connection counters. Queue presence is an observation,
not proof of a bottleneck. Rate gating is an interval delta of the composed local
manual/adaptive gate, never attribution to the manual setting alone. Remote disk/generation causes remain unknown because
the protocol does not report them. Renderer stubs are unavailable capabilities.

`/lss diagnostics export` writes JSON and a text summary under `lss-diagnostics`
in the game directory (`vss-diagnostics` for VSS). The server operator equivalent is
`/lsslod diagnostics export` (VSS: `/vsslod diagnostics export`). Reports contain
typed counters, capability/state facts and cached versions of named loader/mod components. Addresses, names, UUIDs, world
identifiers, seeds, aliases, personal paths and raw exception messages are not
accepted by the exporter. The command displays the local output path; the report
does not contain that path. Nothing uploads automatically. The exporter retains
at most ten reports and admits one active plus one queued export.

The server command acknowledges the queued target path after admission. Check the
server log for completion or a sanitized write failure; the queued message does
not mean the files have been written. Client completion feedback is shown only
while its originating session remains current.

Presets always require a preview followed by an explicit apply:

- Client: `/lss preset map-only` previews enabling reception while preserving the
  existing Xaero write preference. `/lss preset map-only-xaero-writes` explicitly
  selects persistent Xaero map writes too. A compatible consumer is required;
  these LSS settings do not select recipients, uninstall mods or prevent Voxy
  from consuming columns.
- Server: `/lsslod preset pregenerated-world` previews server-global generation
  disabling **for the next restart**. It does not claim the world is completely
  generated. Existing running services keep their effective generation setting.
- Use `preset apply` on the same surface to apply/save the preview, or
  `preset undo` to restore the changed settings from the last application.

A changed relevant value or replaced scope requires a new preview. Undo refuses
conflicting edits and preserves unrelated settings. Undo history expires at
restart, config replacement, or another preset application; client world/session
replacement also expires it. Save failures are reported as unsaved. Restart-only
choices remain staged across subsequent unrelated config saves. Server reports show
running generation separately from the configured restart value and mark a pending
restart. Configured values can remain unsaved after a persistence failure.

`/lsslod preset conservative` previews server radius 32, global generation concurrency 4 and per-player concurrency 1. Review the patch, then use `/lsslod preset apply`; `/lsslod preset undo` restores the changed values when the existing conflict checks allow it. This is opt-in and does not change defaults. The numbers are the settings under which the accepted Minecraft 26.2 V27 reference measurement ran; that measurement did not compare them against the defaults or other operating points, and it does not cover every support line. The six final native server-preset runs and 12 UI runs have completed. The [dated progress index](../implementation/native-validation-progress-2026-09-20.json) records 46 collected passes and 8 automated-green cases awaiting actual user images. Overall user sign-off and full evidence integration remain open; offline suites and game cleanup passed, with the review gallery intentionally live. These functional checks do not broaden the V27 performance claim. The [settings reference](../reference/settings.md) records domains and scope; [performance](performance.md) explains the calibration and limits.

For setup and diagnosis, see [installation](installation.md), [troubleshooting](troubleshooting.md) and [performance](performance.md). Maintainers use the [live status/settings checklist](status-settings-live-acceptance.md) for exact-artifact acceptance.
