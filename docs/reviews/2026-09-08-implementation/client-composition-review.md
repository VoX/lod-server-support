# Independent WI-3 / WI-5 implementation review

Reviewed MC1.21.1 at a6562743 with the implementing agent's current, uncommitted WI-5 source on 2026-09-08. Read-only source/test review; no Gradle or runtime actions. Findings delivered directly to /root/audit03_storage and root.

## P2: dimension cancellation does not retire the receipt's deferred report handle

Sites at review time: `ColumnDelivery.java:34-40`, `LodRequestManager.java:57-64,75-90,1247-1249`, `XaeroMapCompat.java:2276-2287`.

Trigger: an accepted content stamp exists, then an authoritative clear is received but its Xaero acceptance lease remains outstanding. A dimension change calls reportUndispatched/cancelOutstandingDeliveries, which correctly restores the pre-clear positive stamp and saves it. The manager remains acquisition-active across the dimension change. A later Xaero stale-dimension drop or deferred rejection invokes the old receipt handle. isActive checks only manager activity, so it passes; currentDelivery is now false and the different-dimension branch deletes the restored old cache stamp outright.

Impact: cancellation's lost-clear protection is undone. On return, the client claims no content (ts=-1); an all-air response can resolve without sending the clearing payload needed by a consumer that retained old content. This contradicts WI-3's explicit positive-claim preservation requirement.

Fix: give each receipt explicit cancellation/retirement state. Cancelling a receipt must make its handle inactive independently of manager activity, including a delayed event already queued before cancellation. The manager's failure-application guard must recheck that state. Mark stale/superseded pending receipts retired as well while keeping any newer accepted proof unchanged. Keep real, uncancelled cross-dimension failure handling.

Regression: accepted content -> pending clear + retained lease -> production dimension-cancellation/save -> delayed receipt.report/release -> load departed cache; original positive stamp remains and no failure strike is charged. Include report queued before cancellation, uncancelled failure control, and replacement-proof control.

Status: implementing agent independently confirmed the boundary and is adding per-receipt cancellation plus a permanent regression.

## P2: Open-to-LAN host cannot resume acquisition after OFF -> ON

Sites at review time: `ClientSessionGate.java:139-142`, `ClientNetGlue.java:162-175,451-458`; Fabric `LSSServerNetworking.java:68-78` and NeoForge `LSSServerNetworking.java:98`.

Trigger: enter singleplayer (JOIN captures localIntegratedServer=true), then Open to LAN starts the supported LSS service and triggers a host handshake. Once reception works, toggle receiveServerLods OFF then ON. OFF retires the manager. ON requires !localIntegratedServer before announcing; the JOIN latch is still true. triggerHostHandshake only runs when starting the LAN service, which is already running. No manager or handshake is recreated. The same gap exists when reception is OFF while LAN starts and is enabled afterward.

Impact: a supported local LAN host remains without LOD acquisition until a new lifecycle/service-start event, despite enabling the option.

Fix: record or query authorized LAN-host service readiness separately from ordinary integrated singleplayer suppression. Record readiness even when receive is currently OFF; resume can then invoke the normal negotiated handshake path when consumers exist. Reset readiness on the connection lifecycle. Preserve silence for ordinary non-LAN singleplayer and real server permission negotiation.

Regression: localIntegrated JOIN -> host service ready/session -> OFF/ON sends a new handshake; OFF JOIN -> host service ready while OFF -> ON also sends. Ordinary integrated singleplayer without host service remains silent. Drive the production host trigger through the shared gate seam so both loaders share the behavior.

Status: sent to implementing agent; source reachability validated on both loader service-start paths.

## Reviewed controls and limits

Receipt identity/version, queued failure versus acquisition retirement, pending clear chains, accepted proofs, real-consumer failure cap, queue/gate acceptance leases, Xaero origin replacement, acquisition-generation/owed-region identity, and preservation of committed texture rebuilds were inspected. No additional concrete defect was established in this bounded pass. Retaining arbitrary tokenless external reports or acquiring leases after the documented callback scope remains outside the new ownership guarantee; no broader guarantee is assumed.

Implementation/testing of the corrections belongs to the client agent; this report records the pre-correction source findings, not a claim that corrected code has been validated.
