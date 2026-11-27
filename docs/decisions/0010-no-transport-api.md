# 0010 There is no public transport API

Status: accepted 2026-11-18 (maintainer; one refute pass). Request:
[#423](https://github.com/VoX/lod-server-support/issues/423), closed as not planned.

## Decision

The mod offers no supported hook for delivering encoded columns over another transport (an HTTP
endpoint, a CDN, a proxy cache). Columns reach a client on the Minecraft connection, from the
server that checked the request. The supported extension point stays the client consumer API
(`LSSApi.registerColumnConsumer`): what a mod does with a column after it arrived.

An addon may still hook the internals at its own risk. They carry no stability promise, and any
release may break it.

## Why

- A supported hook would freeze request ownership, receipts and session lifetime. Those change
  almost every release (0.23.0 reworked how sessions start and end), on four Minecraft lines and
  four loaders.
- Bytes in a shared cache sit outside the checks the server makes for every request: the distance
  allowed in that world, the `lss.use` permission, whether the client's copy is already current,
  and the wire dialect of that session (v16, v18, v20; raw or zstd).
- The benefit is narrow (large proxy networks). The client's column cache and the "up to date"
  answer already keep unchanged columns from being sent again, and the bandwidth caps, zstd and
  yielding to vanilla traffic bound what a backend uploads.
- For players and operators the default path is the same either way. The cost would fall on
  every later release, as coupling and as a correctness surface the server does not control.

## Consequences

- Requests for transport or proxy hooks are declined with this record.
- An external addon that hooks the internals is not a compatibility target.

## What would reopen it

Numbers from a real server showing that backend upload is the bottleneck. That would start with
a native improvement, not with a hook.
