This independent Folia fixture consumes a checked, cleanly stopped Paper seed
snapshot with the shared source workload's sixteen corner targets. It never
performs synchronous cross-region seeding or save-all on Folia. The runtime must
bind the snapshot digest and load the region observer in `regionOnly` mode.

Loaded target checks and mutations run on each chunk's legal owner. Store frame,
region-header and concurrent loaded-map premises run before clients, off owner
threads; actual client payload provenance remains the acceptance authority.
The shared workload waits for client oracle acknowledgments, produces real
admission/reconnect/slow-consumer controls, and closes offers before debt drain.
The separate region observer supplies owning callbacks qualified by the exact
full-tick observer.

Use `build.py` with explicitly selected Folia engine, library tree and LSS Paper
candidate. `tools/rig/prepare_folia_sources.py` composes the fixture with a reviewed
four-client Folia recipe and `world_snapshot.py` snapshot. This source is not
shipping code and is not a live acceptance claim. Source mix and debt checkers,
qualified region overlap, complete writer shutdown, and the separate measured
protocol must all pass for their respective claims.
