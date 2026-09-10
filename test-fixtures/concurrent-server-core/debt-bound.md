# Completed-result acceptance ceiling

The fixture registers `disk.pending <= 800`: four named subjects × the immutable
`LSSConstants.SYNC_ON_LOAD_SLOT_CAP = 200`. This is an acceptance ceiling for this
workload, not a claim that `ConcurrentLinkedQueue` enforces a capacity.

The source accounting on 26.2 is:

- `IncomingRequestRouter` takes a SYNC slot with `tryAdmit` before a fresh disk
  submission. Its headroom/gate/no-submit unwind paths occur before a result is
  enqueued. Dedup attachments take slots but add no disk result of their own.
- `AbstractPlayerRequestState` holds the slot in `pendingByPosition`; ordinary
  wants changing does not clear the pending map. Duplicate in-flight requests
  cannot admit a second request at the same position.
- `AbstractChunkDiskReader` normal store/header/disk/miss/error outcomes append
  one result. `runRead` deliberately contains Error without rethrowing to avoid
  a second completion from the submit wrapper. The result remains associated
  with that originating registration.
- `OffThreadProcessor.drainDiskResultsForAllPlayers` polls before
  `deliverDiskResult` removes the pending request and releases the slot. Thus
  each queued normal completion still owns a slot. Dedup fan-out happens after
  polling and creates no further completed-result queue entries.
- Primary removal retires its `RequestRegistration` and detaches its result
  queue; cleanup of its attached slots cannot leave a counted primary queue.
  Reconnection replaces the same offline UUID registration. `getPendingResultCount`
  sums only queues currently in `playerResults`, not detached old sinks.
- The controlled scenario permits exactly four registered workload identities;
  the shared debt checker rejects any unexpected name. The login ceiling is
  eight to avoid the engine counting simultaneous pending logins as full, but
  this does not enlarge the accepted workload. Each counted queue therefore
  has at most 200 ordinary queued results. The exporter samples separately, so
  this is an observational acceptance ceiling, not an atomic occupancy snapshot.

The outer submit wrapper retains an unexpected-delivery last-resort path that
can duplicate a completion in exceptional allocation/delivery failure cases.
Those are outside the one-result premise. The lane's checker rejects any
`Unexpected failure delivering disk read` server diagnostic, as well as any
sample above 800; it does not silently widen a failing bound or report a pass
from missing samples. General crash/error gates remain required by the rig.

The disk executor has a different queue bound (reader threads × 32), which does
not bound this exported completed-result gauge and is not used here. Drain is
witnessed by two all-zero samples at least one second apart within 120 seconds
of offers closing, while all four product player states remain registered.
