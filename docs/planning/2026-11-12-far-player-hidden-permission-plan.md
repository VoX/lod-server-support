# Far players: the hide permission no longer hides by itself

Status: decided 2026-11-12 (project owner's direction; maintainer design); implementation on the
0.21.0 train.

## 1. Problem and evidence

Players report that far players never render (Discord, 2026-10-26 and 2026-10-28: Fabric 26.3 with
VSS 0.20.0; a Paper server with a Fabric client). One confirmed way to get there:

- `lss.farplayers.hidden` / `vss.farplayers.hidden` are grant-model nodes: a holder is never served
  as a far player (`FarPlayerBroadcastService`, "Server-authoritative privacy"; Paper
  `PaperFarPlayerSnapshots.hiddenFor`, Fabric/NeoForge `FabricFarPlayerSnapshots.hiddenFor`).
- A wildcard grant resolves every node true. Checked live on Paper 26.3 with LuckPerms 5.5.71:
  after `lp user <name> permission set * true`, `permission check lss.farplayers.hidden` answers
  `true` through `common.WildcardProcessor` (it answers `false` before and after).
- Server owners and admins commonly hold `*`. They are exactly the people who test the feature
  first, and on a small server everyone may hold it. Nothing in the game or the diagnostics tells
  them why nobody is drawn.

Other causes of "far players do not render" stay as they are and are covered by diagnostics below
(fog past about 900 blocks on 26.x with Voxy's default fog mode, server mode `off`/`opt-in`, the
other player's own Share My Position choice, vanish plugins).

A second, smaller trap in the same report: after changing Share My Position the client says
"Sharing preference sent; the server does not acknowledge delivery.", which reads like a failure.
It only means the protocol has no acknowledgement.

## 2. Decision

1. **The permission no longer hides a player by itself.** By default a holder is shown like anyone
   else. Hiding is the player's own choice: Share My Position (the Far Players page of the Sodium
   options, `far_players.sharing.enabled`), which every player already has and which the server
   already honors in mode `on` and requires in mode `opt-in`. Granting every permission therefore
   hides nobody.
2. **New server setting `far_players.hide_permission_holders`** (boolean, default `false`). When
   `true`, every player who holds either spelling of the node is hidden, exactly the behaviour
   before 0.21.0 (wildcards included; that is the operator's explicit choice now).
3. **Everything else on the privacy ladder is unchanged and stays unconditional:** vanish metadata,
   LibsDisguises, `far_players.excluded_players`, spectators, invisibility, the vanish bridge, a
   player's own opt-out and share distance, and Paper/Folia's fail-hidden rule for a throwing read.
4. **Opt-out is not gated on the permission.** Every player keeps the choice they have today;
   taking a privacy control away from players by default is not acceptable. (An operator option to
   restrict opt-outs is possible later and is not part of this change.)

### The upgrade case

Servers that granted the node on purpose (for example to a staff group) will show those players
again after updating until the operator sets `far_players.hide_permission_holders: true`. This is
the accepted cost of removing the trap by default. It must not be silent:

- once per server start, the first time a holder is seen while the setting is off, the server logs
  one INFO line naming the player, saying that the permission no longer hides players by itself
  since 0.21.0, and naming the setting that restores it;
- the release notes lead with it under a "Changed" heading;
- the permission descriptions (`plugin.yml`, Sponge, NeoForge) and the settings reference say it.

## 3. Design

- **Snapshot:** `FarPlayerBroadcastService.PlayerSnapshot` separates the two facts that are one
  boolean today: `hidden` (the unconditional ladder: vanish, disguise, a failed read) and the
  permission (`holdsHidePermission`). Enforcement stays in the service, at the existing
  "Server-authoritative privacy" check: `hidden`, or the permission while the setting is on.
- **Paper/Folia:** `hiddenFor` keeps failing HIDDEN for any throwing read, the permission read
  included, whatever the setting says (a raced read must never leak a vanished player).
- **Fabric/NeoForge/Sponge:** the same split through the existing `LoaderServices.checkPermission`
  seam; both spellings; default `false`.
- **Setting:** server schema, YAML template for new files, `/lsslod set`, reload. An upgraded file
  gains the key only when the setting is changed (the standing rule: older versions reject unknown
  keys, so a file that carries the key loads on 0.20 only after the line is deleted).
- **Diagnostics:**
  - server `/lsslod diag` far-player line gains `hidden=<n>` (players withheld by the privacy
    ladder in the last pass, the forced permission included) and `perm_holders=<n>` when the
    setting is off and holders exist; both only when non-zero;
  - client status screen gains one line with the number of far players the server is currently
    sending, so a screenshot shows at once whether the server sends none (permission, mode,
    opt-outs, distance) or the client is not drawing them (fog, draw range).
- **Client wording:** `lss.settings.privacy_sent` becomes "Sharing preference sent to the server."
  in all three locales.
- **Wire:** unchanged.

## 4. Lines

All six. `common` and the snapshot record are identical on every line; `PaperFarPlayerSnapshots`,
`FabricFarPlayerSnapshots`, the Sponge and NeoForge permission files, the schema and the lang files
carry per-line blobs but the same hunks.

## 5. Tests

- Service: a holder is served when the setting is off and withheld when it is on; `hidden`
  (vanish/disguise) is withheld in both; an explicit Share My Position opt-out is honored in both;
  mode `opt-in` unchanged; both spellings.
- Paper: `hiddenFor` split; a throwing permission read still hides; `PluginYmlContractTest`
  descriptions and defaults (both nodes stay declared, default false).
- Fabric/NeoForge: `FabricFarPlayerSnapshotsHiddenTest` and `LSSNeoPermissionsContractTest` updated
  to the split; Sponge smoke default check unchanged.
- Settings: schema default, template, reload applies without a restart, the upgraded-file rule.
- Diagnostics: tokens appear only when non-zero; existing diag strings unchanged otherwise.
- Live (before release): Paper + LuckPerms with two clients: a `*` holder is shown by default and
  hidden with the setting on.

## 6. Release notes

Changed: "The `lss.farplayers.hidden` permission no longer hides a player by itself, so admins with
every permission show up as far players again. Players hide themselves with Share My Position; set
`far_players.hide_permission_holders: true` on the server to hide every holder as before."
