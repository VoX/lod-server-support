# Actual-client receive toggle regression

Implemented in the existing `fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java` on the four Tier3 lines. No production changes, new world/process infrastructure, captured corpus changes, or 1.21.1 cut-path edits.

## Timeline and assertions

The existing main-flow world remains unchanged. The existing second LAN world now:

1. Preserves the original receive-ON/private-singleplayer controls: no request service, no session config, no client manager before publication.
2. Captures player chunk/view boundary on the actual client thread. Uses `SaveHook.SAVE.run(LSSClientConfig.CONFIG)` to Apply receive OFF, then calls the line's existing real LAN publish overload.
3. Requires the server request service to start while client reception remains OFF; after forty client ticks no session config or client manager may exist.
4. Applies ON through the same SaveHook; requires the real handshake, configured distance, client manager, and one registered server player.
5. A bounded consumer chooses one decoded nonempty position beyond view distance +2 and outside C10's deliberately future-stamped coordinate. It rejects that position exactly once through its captured handle, then retains the re-delivery's acceptance lease. The real client manager must show exactly one setup ingest failure. This establishes an honestly unheld position even when the integrated local cache is warm.
6. Apply OFF must immediately remove the manager while preserving negotiated server-enabled identity. Wait for the decode drain to retire, require the captured receipt inactive, release its lease on the client thread, then wait twenty ticks with unchanged delivery/declaration counts and unchanged one setup failure.
7. Apply ON must produce a distinct manager in the same ClientLevel and re-deliver that exact chosen position. New manager ingest failures and parked positions must both be zero.
8. Finally release any held lease, unregister the bounded consumer, restore the prior receive setting via SaveHook, and restore the integrated-server override.

Only the latest lease for one chosen position is retained; duplicate deliveries replace/release it. No cache flush or synthetic replay packet is used. Real requests, server responses and the normal decode/consumer path supply the re-delivery. Timeout diagnostics distinguish manager absence from traffic without the chosen position.

## Runs

- `tier3-receive-toggle-12110-first.log`: fixture failure after successful OFF retirement. The gametest thread called lease release, whose main-client dispatch reached Minecraft.getInstance; Fabric specifically prohibits that lookup on its test thread. Explicit release and cleanup were moved into `context.runOnClient`. Production callback threads are unaffected.
- `tier3-receive-toggle-12110-second.log`: setup was insufficient. OFF/ON resumed 1,340 columns but did not repeat the first held coordinate (-3,-3). That coordinate overlaps C10's intentionally future-stamped target and could carry valid prior proof/near-vanilla ownership; demanding exact replay was not an established product regression. The final fixture excludes it and establishes unheld proof by one genuine setup rejection outside the view boundary.
- `tier3-receive-toggle-12110-third.log`: **GREEN**, Java21 + `xvfb-run -a ./gradlew :fabric:runClientGameTest --max-workers=1`, complete process **57 seconds**. The LAN segment runs within the existing second world; no extra world boot is added. Worst-case waits remain bounded by the existing 400/600-tick helper budgets, plus a ten-second decode-drain ceiling.

## Commits / ports

| Line | Commit |
|---|---|
| 1.21.10 | 89ac90c1 |
| 1.21.11 | c64f67b8 |
| 26.1 | 41020c0f |
| 26.2 | 84031716 |

26.x conflicts were only the surrounding native publish comments and unnamed resource variable. Kept each line's exact publish overload and Java syntax; 26.2 uses MultiplayerScope.LAN, the other lines retain their GameType form. Root coordinates execution of the other three lines after the port.

## Limits

This is an actual Fabric client + integrated LAN server regression driving the same SaveHook and live gate as Apply. It does not click a Sodium page or establish a modern/legacy Sodium × loader GUI matrix result. It does not run the NeoForge client, 1.21.1 client Tier3 (cut), a remote dedicated connection, or initial receive-OFF at world join. Initial-OFF behavior retains the unit gate coverage; this test specifically covers private-ON -> publication-OFF -> ON -> OFF -> ON with real terrain recovery. It does not reproduce or attribute the previously reported vertical Xaero map lines.
