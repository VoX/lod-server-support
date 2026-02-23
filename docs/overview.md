# Overview

This page describes what LSS does, why it exists, and how data flows through the system.

## Problem

Vanilla Minecraft servers only send chunk data within a player's render distance (typically 8-16 chunks). LOD rendering mods need terrain data far beyond that range to display distant landscapes, but the vanilla protocol provides no mechanism to request it.

## Solution

LSS adds a custom networking layer that lets clients request distant chunk data from the server. The server reads chunks from disk, generates missing ones on demand, serializes the block/biome/light data, and streams it back to the client over bandwidth-controlled connections. LOD mods receive the deserialized sections through a public API and render them however they choose.

The server never sends data the client didn't ask for (except dirty column notifications). The client drives the process by scanning outward from the player in a spiral and batching position requests.

## High-Level Flow

```
1. Client joins server
2. Client sends HandshakeC2S with protocol version
3. Server responds with SessionConfigS2C (LOD distance, batch limits, generation config)
4. Client begins spiral scan outward from player position
5. Client sends ChunkRequestC2S batches (up to 256 positions per batch)
6. Server processes each position:
   a. In-memory chunk  --> serialize immediately
   b. Not loaded        --> submit async disk read
   c. Not on disk       --> submit to generation service (if within generation distance)
7. Server streams ChunkSectionS2C payloads (zstd-compressed) back to client
8. Server sends RequestCompleteS2C when all positions in a batch are processed
9. Client records timestamps, advances spiral, sends next batch
10. After initial scan, server periodically pushes DirtyColumnsS2C for changed chunks
11. Client re-requests only dirty columns on next scan tick
```

## Key Properties

**Bandwidth-controlled.** A global token bucket divides bandwidth fairly among active players. Per-player caps prevent any single connection from monopolizing throughput. The client's pending request limit prevents it from flooding the server.

**Backpressured at every stage.** Every queue in the pipeline is either explicitly capped by configuration or implicitly bounded by an upstream cap. When a downstream stage fills up, upstream stages stall rather than drop work. See [Backpressure](backpressure.md) for the full analysis.

**Persistent client cache.** Column timestamps are saved to disk per-server, per-dimension. On reconnect, the client sends stored timestamps so the server can skip unchanged columns (via fast-path region header comparison) or send only updated data.

**Zero compile-time optional dependencies.** Mod compatibility (e.g., Voxy integration) uses `MethodHandle` reflection at runtime. No optional mod JARs are needed at build time.

**Generation-aware.** The server can generate chunks that don't exist on disk, subject to configurable distance and concurrency limits. Multiple players requesting the same ungenerated chunk piggyback on a single generation task.

## Supported Platforms

| Platform | Role | Minecraft Version |
|----------|------|-------------------|
| Fabric | Client + Server | 1.21.1 |
| Paper | Server only | 1.21.1 |

Fabric clients connect to either Fabric or Paper servers. The wire protocol is identical on both platforms.

## Commands

Both Fabric and Paper provide server-side commands:

- `/lsslod stats` -- per-player metrics (sections sent, bytes, batches, queue sizes)
- `/lsslod diag` -- server diagnostics (config, disk reader stats, generation stats, tick counters)

Fabric also provides a client-side `/lsslod` command for local diagnostics (scan progress, pending columns, cache state).
