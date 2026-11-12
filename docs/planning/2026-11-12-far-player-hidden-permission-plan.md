# Far players: the hide permission no longer hides by itself, and a mode that requires sharing

Status: decided 2026-11-12 (project owner's direction; maintainer design). Scope extended the
same day with server mode `required` (§2b, project owner's direction). Implemented on the 26.3
line for the 0.21.0 train (§7, As built); ports to the five support lines and the six-line
re-pin follow.

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
   before 0.21.0 (wildcards included; that is the operator's explicit choice now). It applies in
   every mode, `required` included.
3. **Everything else on the privacy ladder is unchanged and stays unconditional:** vanish metadata,
   LibsDisguises, `far_players.excluded_players`, spectators, invisibility, the vanish bridge, and
   Paper/Folia's fail-hidden rule for a throwing read.
4. **In the existing modes, opt-out is not gated on the permission.** In `on` and `opt_in` every
   player keeps the choice they have today, and their share distance; taking a privacy control
   away from players by default is not acceptable. Restricting opt-outs is an explicit operator
   choice: mode `required` (§2b).

So the permission means "is allowed to be hidden": automatically with `hide_permission_holders`,
by the player's own choice in mode `required`, and with no effect in mode `on`, where everyone may
hide.

### 2b. Mode `required` (added 2026-11-12)

`far_players.mode` gains a fourth value. No alias: `opt_in` has spelling variants because of its
separator, `required` has none.

| Mode | Who is shown | A player's own opt-out and share distance |
| --- | --- | --- |
| `off` | nobody | not applicable |
| `opt_in` | only players whose own client shares | honored (unchanged) |
| `on` (default) | everyone, players without the mod too | honored for everyone (unchanged); the permission has no effect |
| `required` | everyone, players without the mod too | **ignored**, unless the player holds `lss.farplayers.hidden` or `vss.farplayers.hidden`; a holder's are honored exactly as in `on` |

- `required` overrides only the target's own sharing choices (`shareSelf=false`,
  `shareDistanceBlocks`). The privacy ladder (`hidden`), `excluded_players`, spectators per
  `send_spectators`, invisibility, the vanish bridge and the viewer's own distance preferences
  apply as in every other mode.
- A player who never sent preferences (no client mod) is shown, as in `on`.
- `hide_permission_holders: true` hides holders automatically in `required` too.
- The enable/disable transition treats `required` like `on` (anything but `off` is enabled).

### The honest notice

A player must never believe they are hidden when they are not. While mode `required` overrides a
player's opt-out or share distance, the **server** sends that player one system chat line per
connection:

> This server requires position sharing: other players can see where you are from far away, and
> your Share My Position setting does not apply here.

- It is an ordinary system message from the server, so it reaches every client version. No wire or
  protocol change, no capability bit, no session flag.
- It goes to a non-holder whose retained preferences say `shareSelf=false` or a non-zero share
  distance. It does not go to holders, to players who share anyway, to players without
  preferences, or in any other mode.
- The override is detected in the broadcast pass, where the target's preferences are evaluated, so
  it covers preferences arriving under `required`, the mode switching to `required` at runtime,
  and a player losing the permission while opted out.
- Latched per player per connection and cleared on disconnect (the map-policy chat line's
  pattern). If the mode leaves `required` nothing is sent; if it returns, the line is not repeated
  on the same connection.
- The permission fact is the snapshot's `holdsHidePermission`. On Paper/Folia a throwing read
  yields `hidden` with no permission fact; such a player is withheld and is not told anything in
  that pass (a holder must never be told their setting does not apply).

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
- **Mode `required`:** at the target-preference rungs of the same filter, the target's opt-out and
  share distance bind only when the mode is not `required` or the target holds the permission.
  Modes `on` and `opt_in` take exactly the old path.
- **Notice:** the service detects an overridden player once per online player per pass and sends
  the line through a `PlayerNotifier` each server core supplies (a system chat message to the
  resolved player; `false` when the player cannot be reached, so a later pass retries).
- **Paper/Folia:** `hiddenFor` keeps failing HIDDEN for any throwing read, the permission read
  included, whatever the setting says (a raced read must never leak a vanished player).
- **Fabric/NeoForge/Sponge:** the same split through the existing `LoaderServices.checkPermission`
  seam; both spellings; default `false`.
- **Settings:** `far_players.hide_permission_holders` in the server schema, the YAML template for
  new files and reload; `far_players.mode` validation, the legacy JSON normaliser and the template
  comment know `required`. An upgraded file gains the new key only when the setting is changed
  (the standing rule: older versions reject unknown keys, so a file that carries the key loads on
  0.20 only after the line is deleted). A file that says `mode: "required"` does not load on 0.20
  and older either (strict validation there).
- **Diagnostics:**
  - server `/lsslod diag` far-player line gains `hidden=<n>` (players withheld by the privacy
    ladder in the last pass, the forced permission included), `perm_holders=<n>` when the setting
    is off and holders exist, and `overridden=<n>` (players whose own choice mode `required`
    overrode in the last pass); each only when non-zero;
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
- Service, mode `required`: a non-holder who opted out is served; a non-holder with a
  share-distance limit is served beyond it; a holder who opted out is withheld; a holder with a
  share distance is limited; a holder who did not opt out is served; `hide_permission_holders` on
  hides holders; the privacy ladder, excluded players, spectators and invisibility still withhold;
  the viewer's own distance preferences still apply.
- Notice: sent once per connection to an overridden non-holder; not to holders, sharers, or in
  `on`/`opt_in`; sent when the mode switches to `required` at runtime and when preferences arrive
  under `required`; re-armed after reconnect.
- Modes `on` and `opt_in`: existing tests untouched and green.
- Paper: `hiddenFor` split; a throwing permission read still hides; `PluginYmlContractTest`
  descriptions and defaults (both nodes stay declared, default false).
- Fabric/NeoForge: `FabricFarPlayerSnapshotsHiddenTest` and `LSSNeoPermissionsContractTest` updated
  to the split; Sponge smoke default check unchanged.
- Settings: schema default, template, reload applies without a restart, the upgraded-file rule;
  `required` validates, reloads live and appears in the reference; an unknown value still fails.
- Diagnostics: tokens appear only when non-zero; existing diag strings unchanged otherwise.
- Live (before release): Paper + LuckPerms with two clients: a `*` holder is shown by default and
  hidden with the setting on; in mode `required` an opted-out player without the permission is
  shown and gets the chat line once, and an opted-out holder stays hidden.

## 6. Release notes

Changed: "The `lss.farplayers.hidden` permission no longer hides a player by itself, so admins with
every permission show up as far players again. Players hide themselves with Share My Position; set
`far_players.hide_permission_holders: true` on the server to hide every holder as before."

New: "New server mode `far_players.mode: required`: every player is shown as a far player and
cannot opt out, except holders of `lss.farplayers.hidden`. Players whose choice is overridden are
told in chat."

Also (short lines): the client status screen shows how many far players the server is sending;
"Sharing preference sent to the server." replaces the message that read like a failure.

Downgrade notes for the release preparation: a server file that carries
`hide_permission_holders` (every file 0.21.0 creates) or says `mode: "required"` loads on 0.20 and
older only after deleting that line or changing the mode.

## 7. As built (2026-11-12, 26.3 line, branch `feat/far-player-hide-permission-mc26.3`)

Status: IMPLEMENTED on the 26.3 line, local commits only (not pushed, no re-pin). Ports to the
five support lines and the six-line re-pin are still to do.

**The two facts.** `PlayerSnapshot` carries `hidden` (unconditional) and `holdsHidePermission`
(the grant) as adjacent record components. `FarPlayerBroadcastService.withheldForPrivacy` is the
one decision the filter and the diag count share: `hidden`, or the permission while
`Settings.hidePermissionHolders` is on. `Settings` gained `hidePermissionHolders` as its last
component; both server cores read `config.farPlayersHidePermissionHolders()` when they build it,
once per pass, so a reload takes effect on the next pass.

**Platforms.**
- Paper/Folia: `PaperFarPlayerSnapshots.privacyFor` returns `Privacy(hidden, holdsHidePermission)`
  from one contained block (permission, then vanish metadata, then LibsDisguises). Any throw
  answers `Privacy.FAILED_READ` = hidden, no permission fact, whatever the setting says; the
  builder never sees the setting. `hiddenFor` stays as the verdict-only view. A holder no longer
  short-circuits the ladder, so a vanished or disguised holder is still caught.
- Fabric/NeoForge/Sponge: `FabricFarPlayerSnapshots.holdsHidePermission` (was `hiddenFor`) reads
  both spellings through `LoaderServices.checkPermission(p, node, false)`; `hidden` is the literal
  `false` on this path (vanish is per viewer and rides the `VanishBridge` seam). Sponge has no
  snapshot builder of its own. A doubtful read answers "not a holder" (the seam never throws).

**Upgrade notice.** `noteHolderNotHidden`: an `AtomicBoolean` in the service instance (one per
server start), read once per holder per pass, no allocation after it fires. The line names the
first holder seen, both node spellings, "since 0.21.0", Share My Position and
`far_players.hide_permission_holders: true`. A holder the ladder also hides still counts (the
console is the operator's).

**Mode `required`.** `ownChoiceBinds = mode != required || target.holdsHidePermission()` guards the
opt-out and share-distance rungs; modes `on` and `opt_in` evaluate exactly the old expressions.
`applyMode` is untouched (only `off` disables). `SettingsSchema` validates the fourth value
("Expected off, opt_in, on or required at far_players.mode"), `LegacySettingsMigration` and
`ServerConfigBase.clampFarPlayersMode` know it, `ServerConfigBase.farPlayers()` passes it through
as `required`. No alias.

**The notice.** In the pass's once-per-online-player loop, `ownChoiceOverridden` is true for a
player with retained preferences that opt out or set a share distance, who is not a holder, not
`hidden` and not in `excluded_players`. Such a player is counted (`overridden=`) and told through
`PlayerNotifier.send(uuid, text)`:
- claimed in `overrideNoticed` (a set beside `retainedPrefs`) before the send, released if the
  notifier answers `false` (player not resolvable right now), kept if the send throws (contained,
  one WARN, never retried into spam);
- cleared only in `onDisconnect`, so a viewer shed, a dimension change, a prefs re-send or a mode
  change never repeats the line and a reconnect re-arms it;
- delivery on every platform is `ServerPlayer.sendSystemMessage(Component.literal(text))` to the
  player resolved from the server's player list: the shared core (`RequestProcessingService`,
  server thread) for Fabric, NeoForge and Sponge, and `PaperRequestProcessingService` (the pump
  thread, where the map-policy line is already sent from) for Paper and Folia. Both have a test
  seam like the map-policy sender's.
- The text is `FarPlayerBroadcastService.requiredSharingNotice()`, the approved wording, literal
  English and unbranded (the only other server chat line names the mod inside its sentence; this
  one names the option by its label, which is the same in both brands).

**Settings.** `far_players.hide_permission_holders`: `Spec(..., BOOLEAN, Timing.H, Platform.ALL)`
after `excluded_players`, `ServerSettings.FarPlayers.hidePermissionHolders`,
`ServerConfigBase.farPlayersHidePermissionHolders()`, both templates (key plus comment), and the
reference generator's notes. An upgraded file is not rewritten at startup and gains the key only
when the setting is changed. The template lists the four modes with one line each.

**Diagnostics.** Server: `FarPlayers: …, hidden=<n>, perm_holders=<n>, overridden=<n>`, each only
when non-zero, counted once per online player per pass and cleared when no pass can run (mode off,
no subscriber). Client: `ClientStatusSnapshot.farPlayersFromServer` (schema 6, a count only), the
`lss.status.far_players` line after the renderer line, absent in DH mode.

**Deviations and judgment calls.**
- `/lsslod set` does not exist on this line (removed with the YAML settings; README says so). The
  setting and the mode are live through `/lsslod reload`, like every other hot key.
- The notice skips a `hidden` or excluded player. A raced Paper/Folia read reports `hidden` with
  no permission fact, so a holder could otherwise be told their setting does not apply; and a
  vanished or excluded player is not shown, so nothing is overridden yet. The first pass in which
  the override actually exposes the player sends the line.
- `perm_holders=` and the upgrade notice count every holder seen while the setting is off,
  including one the ladder hides.
- NeoForge permission nodes carry no runtime description (none before either); the class doc and
  the contract test name the setting.
- The client status model took the new field as an appended record component with a
  compatibility constructor (the JourneyMap fields' precedent) and a schema bump to 6; it stayed a
  small change.
- `CLAUDE.md` is unchanged. A one-line invariant ("the hide permission is a fact; never fold it
  back into `hidden`; `required` must tell an overridden player") is worth adding by its owner.

**Tests.** Common: `FarPlayerBroadcastServiceTest` (22 cases before, 45 now; one existing case
rewritten for both settings: the holder served/withheld by the setting, live withdrawal on the
next pass, the ladder unconditional, opt-out and share distance in both settings, `opt-in`
unchanged, the other rungs still binding a holder, the once-per-start notice and its silence,
the diag tokens; in mode `required` a non-holder's choices ignored, a holder's honored,
`hide_permission_holders` on, every other rung and the viewer's distance preferences still
binding, the notice's once-per-connection latch, runtime switch, late preferences, lost
permission, reconnect, unreachable player, throwing lane, hidden or excluded player,
`overridden=`, and the enable/withdraw transition),
`FarPlayerHidePermissionSettingsTest` (default, templates, legacy key, missing key, the
upgraded-file rule, reload on both platforms, `config_version`),
`FarPlayerRequiredModeSettingsTest` (validates and reaches the core, an unknown value still
fails, the four modes in the templates, reload, the legacy normalisers),
`LegacySettingsMigrationTest`, `ClientStatusTextTest` (the count line's place, export, DH mode;
the sharing message in three locales), `ClientSettingsStatusTest` (schema 6). Paper:
`PaperFarPlayerSnapshotsTest` (the split, a vanished or disguised holder, a throw on either
spelling, a holder whose vanish read throws, the snapshot's two components and the raced read
reaching it), `PaperRequestProcessingServiceTest` (the chat lane: unreachable, then exactly one
line), `PluginYmlContractTest`. Fabric: `FabricFarPlayerSnapshotsHiddenTest` (the fact rides the
snapshot, never `hidden`), `FarPlayerHidePermissionWiringContractTest` (both cores pass the live
setting and a chat lane that sends a system message; the builders never see the setting),
`ClientStatusFarPlayerCountTest`, and the gametest
`requiredModeTellsAnOverriddenPlayerOnceInChat` through the service's real pass. NeoForge
`LSSNeoPermissionsContractTest`, Sponge `SpongePermissionsTest`. The existing mode `on` and
`opt_in` cases are untouched and green.

Mutations, each red then restored byte-identical (six batches of the targeted suites, 83 red
results in all): the permission always hiding (the pre-0.21.0 behaviour); the setting never
hiding; the ladder ignored (the leak); the opt-out gated on the permission in mode `on`; the
start notice unlatched, and firing with the setting on; the `hidden=` and `overridden=` tokens
unconditional; the
counts not cleared when the mode goes off; the template defaulting on; restart timing; one
locale missing the status key; the old sharing message; the status line removed and the
tracker count replaced by a constant; Paper failing open, hiding holders, and reading one
spelling; Fabric swapping the two snapshot arguments and reading one spelling; the shared core
passing a constant instead of the setting; the old permission descriptions; `required`
ignoring a holder's choice, still honoring a non-holder's share distance, unknown to the schema
and to the migration; the chat line sent in every mode, to holders, to a hidden player, to an
excluded player, repeatedly, kept after a disconnect, latched for an unreachable player,
retried after a throw; either core built without the chat lane, and the shared core's lane
sending nothing.

Gates on the final tree (2026-11-12, one attempt each, no retries): `run-gradle.sh build
:sponge:build` BUILD SUCCESSFUL (`:common:test` 1210, `:paper:test` 599, `:fabric:test` 2166
with 4 skipped, `:neoforge:test` 25, `:sponge:test` 26, `:fabric:runGameTest` 95 of 95);
`tools/settings/inventory.py --check` and `reference.py --check` clean;
`scripts/release_check.py --selftest` 214 cases OK, `release_check.py` on the built jars clean;
`tools/tests/inventory.py validate-moves` and `check_artifacts.py` clean. `tools/compat/ci.py`
is red as expected until the ports and the re-pin: 41 issues, exactly the 41 non-doc files of
this change (25 shared-divergence, 12 adaptation-review-required, 4 unclassified new tests).
Not run: `:sponge:bootSmoke`, `:neoforge:runGameTestServer`, the client gametests.

**Port notes.** `common`, both templates, the permission files, the Sponge and NeoForge files
and most tests are byte-identical on all six lines before this change, so the commits apply
as-is there. Different per line, same hunks: `PaperFarPlayerSnapshots` and
`FabricFarPlayerSnapshots` (1.21.10 and 1.21.1 spell the dimension accessor `location()`), the
two server cores (1.21.11 and older), `ClientStatus`, `ServiceLifecycleGameTests`, the three lang
files and `plugin.yml` (all lines but 26.2), `PluginYmlContractTest` and `release_check.py`
(every line). The anchors the new source pins use (the vanish lambda's tail, the `Settings`
construction, `sendFarPlayerFrame`) exist on all six lines. README and troubleshooting are
line-local: the fog item of the checklist is a 26.x statement.

**Still to check live.** Paper 26.3 + LuckPerms with two clients: a `*` holder is shown by
default, the INFO line appears once per start, and `hide_permission_holders: true` plus
`/lsslod reload` hides the holder within one update. Mode `required`: an opted-out player
without the permission is shown and gets the chat line once (also on a 0.20 client, and again
after a rejoin); an opted-out holder stays hidden; switching `on` to `required` by reload tells
the opted-out player mid-session; revoking the permission from an opted-out holder shows and
tells them. The same once on Fabric (no permissions mod: nobody is a holder), and a look at
Folia, where a raced read hides for one interval. The client status line with zero and with
some far players.
