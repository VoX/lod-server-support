# Live Paper and NeoForge command persistence checks

2026-09-08, isolated loopback MC1.21.1 fixtures, exact inspected LSS0.14.0 release jars. Paper1.21.1-133 / Java21 and NeoForge21.1.248 / Java21 both loaded the intended candidate and completed this sequence:

1. Save farPlayers=on normally; preserve config SHA256 and mode.
2. Deny writes only to the fixture config directory (0555, UID1000 server), retaining the old readable file.
3. Execute farPlayers=off; wait for actual command completion. The reply includes `applied, but not saved — see server log`; log shows AccessDeniedException creating the sibling temporary config.
4. A separate subsequent listing reports farPlayers=off, while on-disk JSON remains on and byte-identical to the baseline.
5. Restore directory mode0755, execute farPlayers=on, wait for success, then a separate listing confirms on. Final disk value is on.
6. Clean console stop; both final processes exit0. Permissions are restored and the normal real-map server is untouched.

Paper's first attempt batched recovery and stop too closely: its queued recovery did not drain before shutdown. It is not counted as recovery proof. The full sequence was repeated in a fresh Paper process with separate, awaited commands; its complete proof is at18:56:21–18:56:26 local log time. NeoForge's complete sequence is18:57:22–18:57:28. These are fixture sequencing corrections, not product failures.

Evidence: `paper-command-console.log`, `neoforge-command-console.log`, and each platform's `*-command-baseline.json`, `*-command-denial.json`, `*-command-final.json`. All full logs are retained externally. Paper's config directory is its dedicated plugins/LodServerSupport folder; NeoForge uses its fixture-local loader config folder. No other config operations were initiated during the short denial windows.

This proves actual command application, persistence-failure feedback, unchanged prior file and normal save recovery. No clients were connected, so it does not claim visual roster withdrawal/restoration. Fabric's corresponding live command check remains for the GUI fixture lane. The earlier all-line unit tests also cover failure outcomes and brand behavior.
