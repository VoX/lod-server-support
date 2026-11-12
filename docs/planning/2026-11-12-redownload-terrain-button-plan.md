# A "Re-download Terrain" button on the Sodium options page

Status: decided 2026-11-12 (maintainer decision, reviewed); implementation on the 0.21.0 train. Researched on
main at f12fe59da and the five support
lines by `git show`.

**Branch names.** The live 26.1 and 1.21.11 lines are `origin/support/mc26.1-v0.14` and
`origin/support/mc1.21.11-v0.14` (they carry `v0.20.0+mc26.1` / `v0.20.0+mc1.21.11` and the commits
pinned in `config/compatibility/source-refs.json`). The plain `origin/support/mc26.1` and
`origin/support/mc1.21.11` refs are stale at v0.8.1 and must not be used as port targets.

## Decisions and review fold (2026-11-12, authoritative)

Reviewed by two agents (correctness and failure cases; decisions critic). Every finding below was
checked against the code or bytecode. **Where this section and the body differ, this section wins.**

Decisions:
1. **Name and promise.** The action is labelled **"Re-download Terrain"**, not "Clear LOD Cache":
   it clears the download records so the server sends the terrain again; terrain already saved by
   the LOD mod is not deleted. Tooltip: use it if distant terrain looks wrong or has gaps. The
   confirm text says it can take a while and uses bandwidth, and names the stronger fix
   ("to delete saved terrain, use /%s reset", the argument from `Brand.clientCommand()`, so the VSS
   build reads correctly). Catalog id `lss:redownload_terrain`; lang keys `lss.config.redownload_terrain`,
   `.tooltip`, `lss.redownload.*`. No reset button in the dialog.
2. **Scope follows the connection, never the request manager** (critic 1, correctness C3; verified:
   Receive OFF, the map-policy park, a server-side disable and the window before the session config
   all null the manager while connected to an LSS server, and `clearCache` then cleared every
   server). New rule, applied to the command as well:
   - active LSS session, or a DH-terrain connection: this server (unchanged);
   - connected to a remote server with no active download: **clear nothing**; say that distant
     terrain is not being received from this server right now (the dialog shows only Back);
   - singleplayer or not in a world (title screen): every server, and the text says so.
   `ClientCommandActions.clearCacheScope()` returns `CURRENT_SERVER`, `NOT_RECEIVING` or
   `ALL_SERVERS`; the dialog captures the scope when it opens and re-checks it on confirm. If it
   changed, nothing is cleared and the dialog is shown again with the new text. A wider scope than
   the one shown never runs.
3. **A failed clear must not crash the client** (correctness C1; verified: `DhClientSession.drainForReset`
   and the strict sweeps throw on purpose, brigadier catches that for the command, a screen click
   becomes a crash report). The confirm action runs in `try/catch (RuntimeException)`, reports the
   failure by toast and chat, and always returns to the parent screen.
4. **The button must never cost the page or the game** (correctness C2; verified: Sodium validates
   builders in `ConfigBuilderImpl.build()` after `registerConfigLate` returns and crashes the game on
   failure; a blank translated tooltip fails that validation). The walker checks name, tooltip
   (non-blank after translation, with a literal fallback) and consumer itself, builds the button
   completely before `addOption`/`addOptionGroup`, and wraps its own calls in `catch (Throwable)`.
   A test pins that the name and tooltip values are non-blank in every locale.
5. **Dialog details** (correctness C4, C5): Cancel has the initial focus (a held Enter must not
   confirm); `onClose` is overridden to answer "no" and return to the parent.
6. **Feedback:** a short translatable toast; the existing command line (literal, branded) goes to
   chat only.
7. **Legacy Sodium 0.6/0.7 footer button (phase B): not in 0.21.0.** It is reflective layout inside
   a screen LSS does not own and nothing automated renders it. 1.21.10 and Sodium 0.6 players keep
   the command. The release note says "Sodium 0.8 or newer". Revisit as its own item.
8. **26.3 Escape constant:** fixed as its own commit before this feature (`event.isEscape()` on the
   `KeyEvent` lines), with a source pin against a literal 256.
9. **Model size:** keep `ActionSpec`, the enum and the dispatcher; no `withActions`/`findAction`
   helpers unless production needs them. `find(id)` stays options-only.
10. The Sodium row text is "Open" (the body's "Open >" is wrong).

Live checks required before release (private display only): 26.3 Fabric and NeoForge with Sodium
0.9.2 and one 0.8.x client: the row, the dialog over Sodium, Escape press and release, a staged
slider surviving, the toast, the title-screen text, and the Receive-OFF case; the two existing
screens on 26.3.

Release note: "New Re-download Terrain button on the mod's page in Sodium's video settings
(Sodium 0.8 or newer); same as `/lss clearcache`."

## 0. Summary (draft as reviewed)

Add one non-setting **action** to the options catalog. On Sodium 0.8+ it renders as Sodium's own
"external button" row on the General page; clicking it opens a small vanilla confirm screen; on
confirm it calls `ClientCommandActions.clearCache(...)` unchanged, returns to the same Sodium screen
instance, and shows the command's own feedback line as a toast (and in chat when in a world).
On the legacy 0.6/0.7 screen the same action can be a footer button (optional phase B, costed
below). The action is not a setting: nothing in the schema, YAML, `SettingInventoryTest` or
`docs/reference/settings.md` changes.

## 1. How the menu is built today

Data (xplat, `dev.vox.lss.config.menu`, MC-free by design):
- `ClientOptionCatalog.pages()` → two `PageSpec`s (`general`, `far_players`), each a list of
  `GroupSpec`s, each a non-empty list of `OptionSpec` (sealed: `BoolSpec`, `IntSpec`). Each option
  carries a stable `lss:` id, name key, `Tooltip` (fixed or condition-switched keys), `Impact`,
  default, binding over `ClientSettingsEditSession`, `enabledBy`, `SaveHook`, `Visibility`.
- `MenuContext` holds the environment facts tooltips and visibility read (`current()` reads them
  live, contained).
- `ClientSettingsEditSession` is the disk draft both generations edit: setters stage into
  `changes`, `apply()` saves and requests one reload, failed saves keep the draft and open
  `ClientSettingsSaveScreen`.
- `ClientOptionCatalog.serializedDescriptors()/serializedBindings()` are derived from
  `ClientSerializedSettings.descriptors()` (the schema). This, not `pages()`, is what
  `SettingInventoryTest` and `tools/settings/reference.py` (via the Java schema) see.

Renderers (per loader):
- **Modern, Sodium 0.8+**: `LSSConfigMenu implements ConfigEntryPoint` (fabric + same-FQN NeoForge
  twin; the twins differ only in the mod-metadata block). Sodium finds it through the Fabric
  entrypoint `sodium:config_api_user` in `fabric.mod.json`, or on NeoForge through
  `[modproperties.lss] "sodium:config_api_user"` in `neoforge.mods.toml`. `registerConfigLate`
  walks the catalog once at game start into `builder.createOptionPage/Group/BooleanOption/
  IntegerOption`. Fabric compiles against the real Sodium jar (`compileOnly`); NeoForge compiles
  against compile-only stubs in `neoforge/src/main/java/net/caffeinemc/...` that never ship
  (`SodiumNeoGoldenParityTest` pins every stub member against the real sodium-neoforge jar).
- **Legacy, Sodium 0.6/0.7**: `SodiumLegacyOptionsHook` (a `@Pseudo` string-target mixin on
  `net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI`, byte-identical twins) appends pages
  built by the reflective `LegacySodiumPage` at the end of the screen constructor, and calls
  `LegacySodiumPage.fitDonationButtons(this)` after every `rebuildGUI`. `LegacySodiumPage` already
  adds its own widgets to that screen (the tab-pagination arrows): it constructs
  `FlatButtonWidget(Dim2i, Component, Runnable)` reflectively and adds it through
  `AccessorScreenWidgets.lss$addWidget`.
- `SodiumDraftHook` (mixin on `Screen.clearWidgets`) calls `SodiumDraftRefresh.open(screen)` on
  either Sodium screen: unchanged options are reset from the draft, changed (staged) ones are left
  alone.
- `SodiumConfigScreens.open` (ModMenu "Configure") switches on `SodiumGeneration.detect()`.

**Generation selection at runtime.** `SodiumGeneration` is a resource probe (never a class load):
`…/api/config/ConfigEntryPoint.class` present → MODERN; else `…/client/gui/SodiumOptionsGUI.class`
→ LEGACY; else NONE. The two classes never coexist in one Sodium. Rendering itself needs no probe:
the modern walker runs only when a 0.8+ Sodium calls the entrypoint, and the legacy mixin applies
only when its target class exists. Every line ships the legacy stack; five lines ship the walker
(1.21.10 has no 0.8 Sodium, so the walker, its stubs and `LSSConfigMenuTest` are cut there).
1.21.1 is the one line where both generations are real at runtime (Sodium 0.6.13 is the mainstream
build; 0.8.12/0.8.13 exist as well).

## 2. Modelling the action

### 2.1 Catalog (xplat, identical on every line)
- New `ActionSpec` record: `(String id, String nameKey, String tooltipKey, Action action)` with a
  nested `enum Action { CLEAR_LOD_CACHE }`. Compact constructor: `lss:`-namespaced id, non-null
  keys. No MC types (the catalog stays MC-free). The tooltip is a plain key, not a `Tooltip`:
  Sodium's external button takes one fixed `Component` and the legacy button has no tooltip.
- `PageSpec` gains `List<ActionSpec> actions` (immutable copy; a 3-argument constructor and
  `PageSpec.of(...)` keep today's call sites, plus `withActions(ActionSpec...)`).
  `PageSpec.options()` and `GroupSpec` are unchanged, so every existing consumer of options
  (`SodiumDraftRefresh`, `enabledDependencies`, the sealed switches over `OptionSpec`, the pinned
  group shape `(1,1,2,2)` / `(6)`, the pinned id list) is untouched.
- `ClientOptionCatalog`: `ID_CLEAR_LOD_CACHE = "lss:clear_lod_cache"`, attached to the General page;
  `actions()` (flattened) and `findAction(id)`. **`find(id)` keeps returning options only**: Sodium
  0.8 stores the button in `Config.options` beside real options and `SodiumDraftRefresh.visit`
  looks every key up through `find`, so an action must never resolve there.

Why this is not a setting anywhere: `serializedDescriptors()` starts from the schema's stored
keys; `withControlMetadata` maps only those keys; `tools/settings/reference.py` renders the Java
schema; YAML is written from the schema. An `ActionSpec` is in none of them. Gates that prove it:
`SettingInventoryTest` (still 12 UI bindings, key set equals the schema), `python3
tools/settings/reference.py --check` (no diff).

Rejected: a third `OptionSpec` permit (every sealed switch, the defaults/binding round-trip tests
and the draft refresh would have to special-case a thing with no value); a `Consumer` on the spec
(puts a `Screen`-typed lambda into the MC-free catalog).

### 2.2 Dispatch (xplat, MC side)
- `ClientMenuActions.open(ActionSpec.Action, Screen parent)`: the one switch from an action to its
  behaviour (`CLEAR_LOD_CACHE -> ClearCacheConfirmScreen.open(parent)`). Renderers call this and
  know nothing about individual actions.
- The walker's `registerDraft` gets the dispatcher injected (a `BiConsumer<Action, Screen>`;
  production passes `ClientMenuActions::open`) so `LSSConfigMenuTest` can click the recorded button
  and assert `(CLEAR_LOD_CACHE, parent)` without a `Minecraft` instance.

### 2.3 Modern renderer (both `LSSConfigMenu` twins)
After a page's option groups, when `pageSpec.actions()` is non-empty, add one more group:

```java
builder.createExternalButtonOption(Identifier.parse(spec.id()))   // ResourceLocation on 1.21.1
        .setName(Component.translatable(spec.nameKey()))
        .setTooltip(Component.translatable(spec.tooltipKey()))
        .setScreenConsumer(parent -> actions.accept(spec.action(), parent));
```

Facts verified at the classfile level (javap on the jars in the Gradle cache):
- `ExternalButtonOptionBuilder extends OptionBuilder`, so `OptionGroupBuilder.addOption` takes it.
- Sodium requires name, tooltip (non-blank) and the screen consumer
  (`StaticOptionBuilderImpl.validateData`, `ExternalButtonOptionBuilderImpl.validateData`:
  "Screen provider must be set").
- On click, `ExternalButtonControlElement` calls the consumer with **the current
  `VideoSettingsScreen`** (its `screen` field), on mouse click or a focused selection key.
- The row shows the option name on the left and Sodium's fixed text "Open >" on the right
  (`sodium.options.open_external_page_button`); that text is not ours to change.
- The API is present in the oldest modern Sodium (`mc1.21.11-0.8.0-fabric`) and the newest
  (`mc26.3-0.9.3-beta.5-fabric`), so no runtime guard for an old 0.8 is needed. The action group is
  still built inside its own try/catch (one WARN, page kept) so a future API change costs the
  button, not the page.
- No `enabledBy`: it works with "Receive Server LODs" off. `setEnabledProvider` only reacts to
  option state, so "disabled while not connected" is not expressible here anyway (see §3.4).

NeoForge stubs: add `ExternalButtonOptionBuilder` (three methods, each returning
`ExternalButtonOptionBuilder`, descriptors exactly as in §6) and
`ConfigBuilder.createExternalButtonOption`. `SodiumNeoGoldenParityTest` discovers the new stub
class file by itself and checks it against the line's real sodium-neoforge jar.

### 2.4 Legacy renderer: what is feasible
- **An option-row control: not feasible at reasonable cost.** A legacy row needs an `OptionImpl`
  with a storage binding and a `Control` whose `createElement` returns a `ControlElement`, an
  abstract class in Sodium's internals. LSS binds the legacy API with zero compile dependency
  (reflection + `Proxy`), and a `Proxy` cannot extend a class. Reusing `TickBoxControl` or
  `CyclingControl` would make the "button" a staged value that only fires on Apply.
- **A footer button: feasible (phase B).** In the existing `rebuildGUI`-return path
  (`LegacySodiumPage.fitDonationButtons`, called by the hook, which stays byte-for-byte as is),
  when `currentPage` is one of the injected pages and that catalog page has actions, add a
  `FlatButtonWidget(Dim2i, Component, Runnable)` at the footer left `(6, height-30, textWidth+10,
  20)` through the same accessor the tab arrows use. Skip it when it would overlap any child or
  pass `width-211-6` (Sodium's Undo/Apply/Close sit at `width-211`, `width-142`, `width-73`,
  `height-30`; verified in 0.6.13 and 0.7.3), or when the donation buttons were moved into the
  footer. Any failure is contained: no button, one WARN, the command still works.
  - Cost: about 50 lines in `LegacySodiumPage` (two byte-identical twins), one new `SURFACE` row
    (`FlatButtonWidget.<init>/3`, checked against the real 0.6.13 and 0.7.3 jars by
    `SodiumLegacySurfaceResolvesTest`), a pure placement function with a unit test, and **two live
    checks on private-display rigs** (1.21.10 Fabric + Sodium 0.7.3; 1.21.1 NeoForge + Sodium
    0.6.13) because nothing automated renders the legacy screen.
  - Limits: no tooltip (the widget has none); not shown under Reese's Sodium Options (it swaps the
    screen, like the tab fitting today); hidden on very small windows where the footer is taken.
- **Omit on legacy:** zero cost; 1.21.10 (all of it) and most 1.21.1 players (Sodium 0.6.13) keep
  only the command.

Recommendation: ship the modern button on the five walker lines, and do phase B as its own commit
in the same release. If either legacy live check fails or runs out of time, drop phase B for that
line and say "Sodium 0.8 or newer" in the notes. Reason: 1.21.1 with Sodium 0.6.13 is a large share
of players, and they are told to run the command as often as anyone.

## 3. The confirm screen

### 3.1 What to reuse
- `ClientSettingsSaveScreen` and `ClientStatusScreen` are custom `Screen` subclasses. Their render
  code has four line flavors (26.3/26.2, 26.1, 1.21.11/1.21.10, 1.21.1). Not worth copying.
- `/lss reset` confirms in chat (`reset confirm`), no screen.
- **Vanilla `ConfirmScreen` is the right base.** Both constructors have the same descriptor on all
  six lines (javap on each line's named Minecraft jar):
  `(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)V`
  and the five-argument form with yes/no button labels. `callback` is `protected final` on all six.

### 3.2 Why a thin subclass, not a bare `ConfirmScreen`
Vanilla `ConfirmScreen.keyPressed` answers Escape on the **press** (`callback.accept(false)`).
Sodium 0.8+ closes its settings screen on Escape **release** and undoes pending changes when it
does (`VideoSettingsScreen.keyReleased`: `if (hasPendingChanges) undoChanges(); onClose()`).
Returning to Sodium on the press would hand it the release of the same key: the options screen
would close and the player's staged edits would be undone. `ClientStatusScreen` and
`ClientSettingsSaveScreen` already solve this with `ScreenEscapeRelease` (swallow the press, act on
the release). The new screen does the same.

New xplat class `ClearCacheConfirmScreen extends ConfirmScreen` (about 50 lines):
- `open(Screen parent)`: reads the scope (§3.4), builds title/message/buttons, shows the screen.
- callback: on yes run `ClientCommandActions.clearCache(feedback)`; then show `parent` again (the
  same instance).
- `keyPressed`/`keyReleased`: `escape.press(event.isEscape())` / on release `callback.accept(false)`.
  Use `event.isEscape()`, never a literal key code (see the 26.3 note in §8).
- Three line flavors, all in this one file:

| Lines | show a screen | toast manager | key events |
| --- | --- | --- | --- |
| 26.3, 26.2 | `client.setScreenAndShow(s)` (no `Minecraft.setScreen` here) | `client.gui.toastManager()` | `KeyEvent`, `isEscape()` |
| 26.1, 1.21.11, 1.21.10 | `client.setScreen(s)` | `client.getToastManager()` | `KeyEvent`, `isEscape()` |
| 1.21.1 | `client.setScreen(s)` | `client.getToasts()` (`ToastComponent`) | `(int,int,int)`, `keyCode == 256` |

### 3.3 Feedback
`clearCache` already produces one line (literal text with `Brand.shortName()`), for example
"LSS column cache cleared for current server. Chunks will be re-requested." The menu feeds it to:
1. `ClientStatusScreen.sendChatFeedback(message)`, the existing helper for menu-originated feedback
   (adds a chat line when a player exists, so the record matches the command), and
2. a system toast, `SystemToast.add(<toast manager>, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
   <action name>, message)`. `add(…, SystemToastId, Component, Component)` and
   `PERIODIC_NOTIFICATION` exist on all six lines; only the manager accessor differs (table above).
   The toast is what the player sees while still in the menu, and the only feedback on the title
   screen, where there is no chat.

A status line on the confirm screen is not possible (it closes), and a second "done" screen costs
an extra click.

### 3.4 Not connected, singleplayer, other servers
`clearCache` today (unchanged):
- LSS session active (`ClientNetGlue.getRequestManager() != null`): clears this server's saved
  column records (every declared spelling), resets request state; columns are requested again.
- DH-terrain connection: drains and resumes the DH session, clears this server's receipts.
- **Anything else: `ColumnCacheStore.clearAll()`, every server's cache.** This includes the title
  screen, a server without LSS, and **singleplayer** (`ClientNetGlue.onJoin` never activates a
  session on an integrated server). The command already does this when typed there; the menu makes
  it reachable from the title screen too.

Recommendation: keep the button always enabled and make the confirm text name the scope.
- Add `ClientCommandActions.clearCacheScope()` returning `CURRENT_SERVER` or `ALL_SERVERS`, with
  the same predicate `clearCache` uses. `clearCache` itself is not restructured; a unit test pins
  that the scope and the feedback text agree in each state (`DhClientSessionTest` already calls
  `clearCache` under JUnit and checks "current server").
- Message for `CURRENT_SERVER`: this server's distant terrain will be downloaded again.
  Message for `ALL_SERVERS`: you are not on a server that sends distant terrain, so this clears the
  download records for every server.
- Both say that terrain already saved by the LOD mod is not deleted (that is `/lss reset`).

Why not disable it when not connected: the modern API cannot express a live "connected" condition
(`setEnabled(boolean)` is fixed at registration, `setEnabledProvider` follows option state only),
clearing is harmless (a re-download), and a greyed button with no reason shown is worse than a
dialog that says exactly what will happen.

### 3.5 Unsaved option drafts
- The action never touches `ClientSettingsEditSession` or `LSSClientConfig.CONFIG.edits()`.
- Sodium's staged values live in its option objects (0.8+: `ConfigManager.CONFIG`; legacy: the
  `OptionImpl`s in the screen's page list), not in widgets. We return to the **same screen
  instance**; its `init()` rebuilds widgets only. LSS's `SodiumDraftHook` then runs
  `SodiumDraftRefresh.open(screen)`: same screen identity, so no disk re-read, and only unchanged
  options are reset. Staged edits are neither applied nor discarded.
- Never create a new settings screen on return (`createScreen` resets all options from bindings).
- Live check item: after returning, a staged slider still shows its staged value and Apply is still
  lit. Also note whether the 0.8+ screen jumps back to its first page (cosmetic).

## 4. Branding and translations
- The VSS jar rewrites lang **values** (`LOD Server Support` → `Voxy Server Side`, `\bLSS\b` →
  `VSS`) in every `assets/lss/lang/*.json`; keys stay `lss.*`. `release_check` pins that no value
  still says LSS. So new strings may say "LSS" but must not contain a lowercase command such as
  `/lss clearcache` (not rewritten, not caught). Keep label, tooltip and dialog text brand-free and
  do not name the command in them.
- The feedback line comes from `Brand.shortName()` already.
- New keys (single source `fabric/src/main/resources/assets/lss/lang/`, copied into the NeoForge
  jar by its build): `lss.config.clear_lod_cache` ("Clear LOD Cache"),
  `lss.config.clear_lod_cache.tooltip`, `lss.clear_cache.title` ("Clear LOD cache?"),
  `lss.clear_cache.this_server`, `lss.clear_cache.all_servers`, `lss.clear_cache.confirm`
  ("Clear cache"). Cancel uses vanilla `CommonComponents.GUI_CANCEL`.
- All three files on every line: `en_us.json`, `zh_cn.json`, `zh_tw.json`
  (`ClientOptionCatalogTest.everyLangFileCarriesTheSameKeys` pins equal key sets; the zh files are
  real translations, so write real ones). The lang blobs differ per line, so the port is "add the
  same six keys", not a file copy.
- The two `lss.config.*` keys must come from the catalog: `ClientMenuEntrypointContractTest` and
  `SodiumConfigApiContractTest` forbid `"lss.config.` literals and `lss:` ids in the walker sources.

## 5. Tests

Existing pins and how they move:
- `ClientOptionCatalogTest`: option ids, group shape and impacts stay as pinned. Add: action ids
  unique, `lss:`-namespaced, disjoint from option ids; `find(actionId)` is empty and
  `findAction` resolves; action name/tooltip keys are in `en_us.json`; the shipped action shape
  (`general` → `[lss:clear_lod_cache]`, `far_players` → none); the six new keys in all three
  locales (already covered by the key-set test).
- `SettingInventoryTest`: unchanged and must stay green (12 UI bindings). Add one assert that no
  serialized descriptor carries the action id.
- `LSSConfigMenuTest` + Fabric test stubs (`fabric/src/test/java/net/caffeinemc/.../structure/`):
  add `ExternalButtonOptionBuilder`, `ConfigBuilder.createExternalButtonOption`,
  `RecordedExternalButton`; let `RecordedGroup` hold `OptionBuilder`s. The stubs shadow the real
  API at test time, so a missing stub method is a `NoSuchMethodError` in this test. Existing loops
  that cast every recorded option to `RecordedOption` filter by type. New test: the General page's
  last group is exactly one external button with the catalog's id, name and tooltip; the recorded
  consumer forwards `(CLEAR_LOD_CACHE, parent)` to the injected dispatcher; "twelve explicit
  controls" still counts settings only.
- `SodiumConfigApiContractTest` (NeoForge) and `ClientMenuEntrypointContractTest` (Fabric): add
  "the walker iterates `pageSpec.actions()`" to the source pins, so a twin cannot drop the button.
- `SodiumNeoGoldenParityTest`: no edit; it picks up the new stub and compares it with the real jar.
- `SodiumGenerationTest`, `SodiumLegacyHookContractTest`: no change (the hook body and its two
  injects are untouched; phase B lives inside `LegacySodiumPage`).
- `NeoForgeModuleContractTest.loaderSurfaceFreeTwinsAreByteIdentical`: phase B must land in both
  `LegacySodiumPage` twins in one commit.
- Phase B: `LegacySodiumPageTest` gets the placement function's cases (fits; overlaps a child;
  too narrow; donation buttons in the footer); `LegacySodiumPage.SURFACE` gets the
  `FlatButtonWidget` constructor row, which `SodiumLegacySurfaceResolvesTest` checks against the
  real 0.6.13 and 0.7.3 jars.
- New `ClearCacheActionTest` (fabric tests): `clearCacheScope()` against the three states and its
  agreement with the feedback line; source pins that `ClearCacheConfirmScreen` uses
  `ScreenEscapeRelease`, shows `parent` (never `createScreen`), and never references
  `LSSClientConfig.CONFIG.edits()`.

Client gametest (worth one, on the five lines that have the task; 1.21.1 has none): open
`ClearCacheConfirmScreen` over a dummy parent in `LSSClientGameTests`; "Clear cache" restores the
parent and runs the action once; Cancel and Escape restore the parent and run nothing. It is the
only automated run of the per-line show-screen/toast/key flavor. Sodium is `compileOnly`, so the
button row itself cannot be gametested without adding a Sodium runtime arm (not proposed).

Live checks (private display only, never the user's foreground): 26.3 Fabric with Sodium 0.9.2;
26.3 NeoForge with sodium-neoforge 0.9.2; one 0.8.x client (1.21.11 or 26.1); phase B rigs. Check:
the row shows on General; Escape on the dialog returns to Sodium without closing it; a staged edit
survives; the toast shows; the title-screen dialog says "every server".

## 6. Per-line table

Descriptors are from `javap -s` on the named jar.
`EBOB` = `Lnet/caffeinemc/mods/sodium/api/config/structure/ExternalButtonOptionBuilder;`.
`setScreenConsumer` is `(Ljava/util/function/Consumer;)EBOB` in every modern jar.

| Line | Loader | Sodium compiled against | Generation(s) at runtime | `ConfigBuilder.createExternalButtonOption` | Gets |
| --- | --- | --- | --- | --- | --- |
| 26.3 | Fabric | `mc26.3-0.9.2-fabric` (`compileOnly`, `sodium_version`) | MODERN (0.9.2, 0.9.3 betas) | yes: `(Lnet/minecraft/resources/Identifier;)EBOB` | button |
| 26.3 | NeoForge | stubs; golden `mc26.3-0.9.2-neoforge` (nested `…-mod.jar`) | MODERN | yes: same descriptor | button |
| 26.2 | Fabric | `mc26.2-0.9.1-beta.3-fabric` | MODERN (0.9.0 to 0.9.2) | yes: `(…/Identifier;)EBOB` | button |
| 26.2 | NeoForge | stubs; golden `mc26.2-0.9.1-beta.3-neoforge` | MODERN | yes: same | button |
| 26.1 | Fabric | `mc26.1.2-0.8.12-fabric` (literal in `fabric/build.gradle`) | MODERN (0.8.7 to 0.9.2) | yes: `(…/Identifier;)EBOB` | button |
| 26.1 | NeoForge | stubs; golden `mc26.1.2-0.8.12-neoforge` | MODERN | yes: same | button |
| 1.21.11 | Fabric | `mc1.21.11-0.8.12-fabric` (`modCompileOnly`) | MODERN (0.8.0 to 0.8.15 beta) | yes: `(Lnet/minecraft/class_2960;)EBOB` (intermediary for `Identifier`) | button |
| 1.21.11 | NeoForge (built, not shipped) | stubs; golden `mc1.21.11-0.8.12-neoforge` | MODERN | yes: `(…/Identifier;)EBOB` | button |
| 1.21.10 | Fabric | none (no 0.8 Sodium; legacy golden `mc1.21.10-0.7.3-neoforge`) | LEGACY only (0.7.x) | absent (class not in 0.7.3) | phase B footer button, else command only |
| 1.21.10 | NeoForge (built, not shipped) | none | LEGACY only | absent | same |
| 1.21.1 | Fabric | `mc1.21.1-0.8.13-beta.2-fabric` (`modCompileOnly`) | MODERN with 0.8.12/0.8.13; LEGACY with 0.6.13 | modern: yes, `(Lnet/minecraft/class_2960;)EBOB` (intermediary for `ResourceLocation`); 0.6.13: absent | modern: button; legacy: phase B, else command |
| 1.21.1 | NeoForge | stubs; golden `mc1.21.1-0.8.13-beta.2-neoforge`; legacy golden `mc1.21.1-0.6.13-neoforge` | MODERN or LEGACY | modern: yes, `(Lnet/minecraft/resources/ResourceLocation;)EBOB`; 0.6.13: absent | same |

Also verified: `mc1.21.11-0.8.0-fabric` (oldest modern release) and `mc26.3-0.9.3-beta.5-fabric`
(newest) both carry the method and `setScreenConsumer`.

## 7. Files, steps, port classification

### 7.1 Files on main (26.3)
New:
- `xplat/src/main/java/dev/vox/lss/config/menu/ActionSpec.java`
- `xplat/src/main/java/dev/vox/lss/config/menu/ClientMenuActions.java`
- `xplat/src/main/java/dev/vox/lss/config/menu/ClearCacheConfirmScreen.java`
- `neoforge/src/main/java/net/caffeinemc/mods/sodium/api/config/structure/ExternalButtonOptionBuilder.java` (compile-only stub)
- `fabric/src/test/java/net/caffeinemc/mods/sodium/api/config/structure/ExternalButtonOptionBuilder.java`, `RecordedExternalButton.java` (test stubs)
- `fabric/src/test/java/dev/vox/lss/config/menu/ClearCacheActionTest.java`

Changed:
- `xplat/.../config/menu/PageSpec.java`, `ClientOptionCatalog.java`
- `xplat/.../networking/client/ClientCommandActions.java` (add `clearCacheScope()`; `clearCache`
  body untouched)
- `fabric/.../config/LSSConfigMenu.java`, `neoforge/.../config/LSSConfigMenu.java`
- `neoforge/src/main/java/net/caffeinemc/.../structure/ConfigBuilder.java`
- `fabric/src/main/resources/assets/lss/lang/{en_us,zh_cn,zh_tw}.json`
- tests: `ClientOptionCatalogTest`, `SettingInventoryTest`, `LSSConfigMenuTest`,
  `ClientMenuEntrypointContractTest`, `SodiumConfigApiContractTest`, fabric test stubs
  `ConfigBuilder`, `RecordingConfigBuilder`, `RecordedGroup`; `LSSClientGameTests` (gametest)
- phase B: both `LegacySodiumPage.java` twins, `LegacySodiumPageTest`
- docs (line-local): `README.md` (the command list and the two "run `/lss clearcache`" passages
  gain "or the Clear LOD Cache button on the Sodium options page"),
  `docs/operations/troubleshooting.md`, the plan record under `docs/planning/`.
- `config/lines/classification.json` (new paths), then the six-line re-pin.

### 7.2 Steps
1. Failing tests first: catalog action pins, the `LSSConfigMenuTest` external-button test, the
   scope test.
2. `ActionSpec`, `PageSpec.actions`, the catalog entry, `findAction`; lang keys in three locales.
3. `clearCacheScope()` and its parity test.
4. `ClearCacheConfirmScreen` and `ClientMenuActions`.
5. Fabric walker + Fabric test stubs; NeoForge walker + compile-only stubs. Run the Fabric and
   NeoForge test tasks (the NeoForge golden needs the sodium-neoforge jar resolved; `CI=true`
   makes a missing jar fail).
6. Client gametest (`verify.py … --run` on a private display).
7. Phase B (own commit): `LegacySodiumPage` twins, `SURFACE` row, placement test.
8. `python3 tools/settings/reference.py --check`, `python3 scripts/release_check.py --selftest`,
   `bash tools/verify/run-gradle.sh vssJars` + release check (the VSS lang pin), full gate.
9. Live checks; then port, classify, re-pin.

### 7.3 Classification for the ports
Identical on all six lines (new or already byte-identical today):
`ActionSpec.java`, `ClientMenuActions.java`, `PageSpec.java`, both `LegacySodiumPage.java` twins,
`LegacySodiumPageTest.java`, `ClearCacheActionTest.java` (if written against the scope function and
source pins only).

Adapted (same hunk on a line-flavored file):
- `ClearCacheConfirmScreen.java`: three flavors (table in §3.2). New entry with `axes` naming them.
- `ClientOptionCatalog.java`, `ClientCommandActions.java`: existing comment flavors on 1.21.x.
- `fabric/.../LSSConfigMenu.java`, `neoforge/.../LSSConfigMenu.java`, the NeoForge stubs, the
  Fabric test stubs, `LSSConfigMenuTest.java`: `ResourceLocation` on 1.21.1; **absent on 1.21.10**
  (`reviewed_blobs` null there, as today).
- `ClientOptionCatalogTest.java`: 1.21.10 has its own last test.
- `SodiumConfigApiContractTest.java`: absent on 1.21.10.
- `en_us.json`, `zh_cn.json`, `zh_tw.json`: per-line blobs; add the same six keys.
- `LSSClientGameTests.java`: absent on 1.21.1.

Line-local: docs.

### 7.4 Release-notes bullet
- Added a Clear LOD Cache button to the mod's page in Sodium's video settings (same as `/lss clearcache`).

(If phase B is dropped on a line, that line's note adds "Sodium 0.8 or newer".)

## 8. Risks and notes
- Nothing automated runs a real Sodium. The external-button row inside a normal option group is
  verified against bytecode, not on screen; the live checks in §5 are the gate.
- The Escape trap (§3.2) is the one way this feature could discard a player's staged settings.
  It needs the gametest and a live check on a modern Sodium.
- **26.3 finding to fix in the same change (recommended):** on 26.3 `InputConstants.KEY_ESCAPE` is
  41 and `KeyEvent.isEscape()` tests `input() == 41` (26.2 and older: 256). Main's
  `ClientStatusScreen` (lines 69, 72) and `ClientSettingsSaveScreen` (lines 60, 63) still compare
  `event.key() == 256`, so their Escape guard never fires on 26.3 and Escape falls through to
  vanilla's close-on-press. Over Sodium's screen that is the trap described above (Sodium closes
  and undoes pending changes). `StatusScreenDrawOrderTest` only checks that the guard is called,
  not the constant. Switch both to `event.isEscape()` on the five `KeyEvent` lines and pin it.
  Evidence is classfile-level; confirm once on a 26.3 client.
- `clearAll()` blocks the client thread until the cache directories are deleted, exactly as the
  command does.
- Phase B adds reflective layout code to a screen LSS does not own; it is contained, but only the
  live rigs prove the layout.
- Open decisions: (1) phase B in or out; (2) always enabled with scope text, versus disabled when
  not connected; (3) toast + chat, versus chat only; (4) fix the 26.3 Escape constant here or
  separately.

## 9. As built (26.3, 2026-11-12)

Implemented on `feat/redownload-button-mc26.3` as folded above. What differs from the body and
from the fold, and why:

**Names.** The screen is `RedownloadTerrainConfirmScreen`, the action `REDOWNLOAD_TERRAIN`, the
id `lss:redownload_terrain`. Lang keys: `lss.config.redownload_terrain`, `.tooltip`, and
`lss.redownload.title`, `.title.not_receiving`, `.this_server`, `.all_servers`,
`.not_receiving`, `.saved_terrain` (carries `/%s reset`), `.confirm`, `.toast.this_server`,
`.toast.all_servers`, `.failed` (12 keys, three locales).

**Model.** `PageSpec` is a four-component record (`actions` last). `PageSpec.of(...)` builds an
option-only page; the General page uses the canonical constructor. There is no `withActions`,
no catalog-level `actions()` and no `findAction`: nothing in production needs them.

**Scope (§3.4 is replaced).** `ClientCommandActions.ClearCacheScope` and `clearCacheScope()`;
`clearCache` was restructured around it (the body said "not restructured"). One read of the
request manager decides the scope and is the manager that gets flushed.
- `CURRENT_SERVER`: a request manager exists, or `DhClientSession.dhServerConnection()` on a
  remote server (narrowed by the review fold below to connections that can also download). That is the predicate `/lss reset` already uses for "a DH session that can
  refill": the server has negotiated DH. The old test (`DhClientSession.selected()` and a
  connection) is true on every server without native LSS while "Allow DH Server Terrain" is
  on, which is the default, so it would have promised a re-download on servers that send
  nothing.
- `NOT_RECEIVING`: connected, not the integrated server, neither of the above. The command
  prints "... is not downloading distant terrain from this server right now. Nothing was
  cleared."
- `ALL_SERVERS`: no connection, or `hasSingleplayerServer()`.

Resulting changes to the typed command, all intended:
1. On an LSS server without a request manager (Receive OFF, map-policy park, server-side
   disable, before the session config) it clears nothing. It used to clear every server.
2. On a remote server where DH is allowed but not negotiated (no LSS and no DH, or a DH
   channel that has not answered, e.g. official DH 3.2.x) it clears nothing. It used to clear
   that server's records and report "No active DH download service".
3. A DH source that has stopped (a failed session or a failed reset) counts as not receiving,
   so `clearcache` no longer restarts it. Reconnecting, turning Receive off and on, or
   `/lss reset confirm` do.
`clearCacheInDhModeClearsOnlyTheCurrentRemoteAddressReceipts` now negotiates first, and
`clearCacheWhileWaitingForADhReplyClearsNothingAndKeepsTheSession` pins case 2.

**Confirm screen.**
- `open(parent)` is itself contained (`catch (RuntimeException)`, one WARN): a failure to
  build the dialog must not crash the client from a settings click either.
- The not-receiving variant has its own title ("Nothing to re-download") and overrides
  vanilla's `addButtons(LinearLayout)` to add only Back.
- The toast title is the button's name; the body is `lss.redownload.toast.*` or
  `lss.redownload.failed`. On failure the same failure line also goes to chat.
- A second answer on the same instance is ignored.
- The scope read and the clear are constructor parameters (`create(parent, scope, clear)`,
  public for the gametest); production passes `ClientCommandActions`.

**Walker.** `addActions(builder, page, pageSpec, actions)` in both twins. A blank translated
tooltip becomes the literal "Opens a screen for this action." (generic on purpose: the walker
knows nothing about individual actions). A blank name, a missing dispatcher or any `Throwable`
drops the page's buttons with one WARN; the page keeps its options.

**Tests.** As §5, with these differences: the scope tests are `ClearCacheScopeTest`
(`fabric/.../networking/client`, next to the package-private seams) plus the two DH cases in
`DhClientSessionTest`; the screen's source pins are `RedownloadTerrainConfirmScreenContractTest`;
`ScreenEscapeKeyContractTest` finds every screen that owns a `ScreenEscapeRelease` and pins
`event.isEscape()` (the 26.3 fix, and this screen); the NeoForge contract test also compares the
twin's `addActions` with Fabric's as text; `LSSConfigMenuTest` covers the blank-tooltip
fallback, a blank name, a missing API (`NoSuchMethodError`) and a missing dispatcher; the
client gametest also covers a changed scope, the Back-only variant and a throwing clear.

**Cleanup in the same branch.** Removed `ClientOptionCatalog.descriptor(OptionSpec)` (no
caller), the unused `find` helper in `LSSConfigMenuTest`, the unused `readEnumOption` in the
NeoForge `ConfigState` stub, and the `CyclingControl` test in `LegacySodiumPageTest` (production
has no such path). `LegacySodiumPage.SURFACE` gained `SodiumOptionsGUI.setPage/1`,
`FlatButtonWidget.<init>/3` and `FlatButtonWidget.visible` (present in 0.6.13 and 0.7.3).
Three stale comments fixed.

**Port notes.**
- Same text on every line: `ActionSpec`, `PageSpec`, `ClientMenuActions`, the
  `ClearCacheScope` rule and `clearCache`, the lang keys (added to each line's own files),
  `ClearCacheScopeTest` apart from its harness, the docs.
- `RedownloadTerrainConfirmScreen`: per line as in §3.2, plus `addButtons(LinearLayout)` and
  the `yesButton`/`noButton` fields, which must be checked against each line's `ConfirmScreen`.
- `ScreenEscapeKeyContractTest` and the Escape fix: the five `KeyEvent` lines; 1.21.1 keeps
  `(int,int,int)` and `keyCode == 256`, so its pin needs the opposite flavor.
- The walker, its stubs, `LSSConfigMenuTest` and the two source pins: `ResourceLocation` on
  1.21.1, absent on 1.21.10.
- `ClearCacheScopeTest` and the `DhClientSessionTest` cases use `hasSingleplayerServer()`,
  `LocalPlayer.connection` and `ServerData.ip` through a constructor-free `Minecraft`; check
  those names per line.

**Review fold (2026-11-12, after the merge into the release branch).**
1. *A DH connection that cannot download no longer promises a re-download.* The DH arm of the
   scope is `dhServerConnection() && canAcquire()`. A negotiated connection where the server
   offers neither sync nor generation (pushes only), where sync on load is off locally, where
   the Voxy bridge failed, or whose level or connection is in mid-switch is `NOT_RECEIVING`:
   nothing is cleared, the session is left alone, and the dialog shows the Back-only variant.
   The other shape (clear the receipts anyway, with its own text) was not taken: the
   mid-switch state lasts less than a client tick, and right after a dimension change the new
   session is already "not receiving" until its config arrives, so a fourth dialog variant
   would not have spared the player that answer. With this rule "will be downloaded again" is
   true whenever it is shown, and the command's "No active DH download service" sentence is
   gone because that state no longer clears anything.
2. Because a push-only server does send terrain updates, the not-receiving texts now say
   "not being **downloaded**" (dialog) and "is not downloading distant terrain from this
   server right now. Nothing was cleared." (command).
3. `lss.status.dh.state.stopped` names the way back: reconnect, or turn Receive Server LODs
   off and on. Both were checked: a stopped session stays installed tick after tick; Receive
   off drops it, and so does a disconnect, after which the status is "waiting for the next DH
   session".
4. `lss.redownload.all_servers` no longer says "not on a multiplayer server" (untrue for a
   LAN host): "You are not connected to a server that sends distant terrain, ...".
5. Tests: the three cannot-download causes, a negotiated-then-stopped session and the
   restart paths in `DhClientSessionTest`; every locale keeps en_us's format placeholders,
   key by key, in `ClientOptionCatalogTest`.

**Not verified here.** No real Sodium ran. The row on the General page, the dialog over
Sodium's screen, a staged slider surviving the round trip, the toast and the Receive-OFF case
remain the live checks listed in the fold section.

