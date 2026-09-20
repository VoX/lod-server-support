# Astra live validation profiles

Inventory captured 2026-09-08 from embedded mod metadata, not just filenames: [full hashes/components](../reviews/2026-09-08-implementation/prism-inventory.json). Installation is evidence of a candidate profile, not proof that a mod was loaded or its feature exercised. Append launch logs and observations to the implementation ledger for every completed live gate.

The Prism root is `/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances`; actual mods are under each instance's **minecraft/mods** directory. Preserve disabled jars and personal profiles. Fault-injection gates should use isolated clones with a clearly recorded artifact set.

| Instance | Intended role | Current coverage boundary |
| --- | --- | --- |
| lss-test-1.21.1 | Fabric modern Sodium + Xaero | MC1.21.1 / Sodium0.8.13-beta.2 / Xaero1.45.0. No enabled Voxy artifact: valid candidate for Xaero-only checks, not Voxy certification. |
| LSS dev — 1.21.1 NeoForge - Copy For Far Testing | NeoForge Xaero / far-player / Voxy reset | MC1.21.1 / native Sodium0.8.12-beta.1 / Voxy0.2.15-beta through Connector + Forgified Fabric API. This profile contains the inspected old-holder/namespaced-reset shape. Fabric metadata on this Voxy jar is intentional with Connector; verify transformed artifact and launch log. |
| lss-test-neo-1.21.1 | Dedicated NeoForge test profile | Enabled Voxy0.2.16 jar's metadata targets1.21.11; resolve that MC mismatch in an isolated validation profile before treating it as a Voxy1.21.1 gate. Legacy Sodium0.6.13 and native community Voxy jars are parked disabled; a separate compatible clone is needed for the legacy UI gate. |
| lss-test-1.21.10 | Fabric legacy UI / wing animation | Sodium0.7.3 / Voxy0.2.9-alpha / Xaero1.45.0; confirm loaded versions before live use. |
| lss-test-1.21.11 | Fabric modern UI / wing animation | Sodium0.8.2 / Voxy0.2.9-alpha / Xaero1.45.0; confirm actual modern API availability. C2ME server validation has two explicitly named profiles on this line. |
| lss-test-26.1 | Fabric26.1.2 terrain and map | Sodium0.9.1 / Voxy0.2.18-beta / Xaero1.45.0. |
| lss-test-26.2 | Fabric26.2 terrain and map | Sodium0.9.1 / Voxy0.2.18-beta / Xaero1.45.0, minimap26.4.2. |
| lss-test-neo-1.21.11 | NeoForge maintained build | No installed Voxy/Sodium/Xaero in the selected inventory; not an optional-integration gate. Shipping flag is false. |
| lss-test-neo-26.1 | NeoForge26.1.2 optional terrain | Inspect Connector/transformed Voxy pairing; far-player renderer remains an intentional stub despite terrain pairing. |
| lss-test-neo-26.2 | NeoForge26.2 optional terrain/map | Inspect Connector/transformed Voxy pairing; far-player renderer remains an intentional stub. |

Current `.github/line.env` ships NeoForge on1.21.1,26.1 and26.2;1.21.10/1.21.11 builds remain maintained but not shipped. This does not rewrite historical release decisions. There is no Folia1.21.1 build, and no Tier3 client gametest on that line. A successful unit, bytecode or artifact check must not be labeled a live UI/rendering result.

For live gates record: commit, installed jar SHA256, instance/clone path, actual launch mod list, loader/MC versions, server fixture path/port, action timeline, expected observation and outcome. Do not record account credentials or launcher authentication arguments. Preserve the normal real-map server and treat its world as nondisposable.
