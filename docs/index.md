# LOD Server Support

LOD Server Support (LSS) distributes LOD chunk data from Minecraft servers to clients over a custom networking protocol. Servers read chunks from disk or generate them on demand, then stream serialized sections back to clients, enabling LOD rendering mods like [Voxy](https://modrinth.com/mod/voxy) to display terrain far beyond vanilla render distance.

## Quick Start

- **Server admins** -- see [Configuration](configuration.md) for tuning bandwidth, LOD distance, and generation limits
- **LOD mod developers** -- see [API Guide](api.md) for integrating with the `ChunkSectionConsumer` interface
- **Contributors** -- see [Development](development.md) for building, testing, and project conventions

## Documentation

| Page | Description |
|------|-------------|
| [Overview](overview.md) | What LSS is, why it exists, and how it works at a high level |
| [Architecture](architecture.md) | Project structure, components, and the server processing pipeline |
| [Protocol](protocol.md) | Protocol v5 wire format specification for all 8 payload types |
| [Client](client.md) | Client-side spiral scanning, batch management, caching, and resync |
| [Configuration](configuration.md) | All config fields with defaults, valid ranges, and tuning guidance |
| [Backpressure](backpressure.md) | How flow control works end-to-end across the pipeline |
| [API](api.md) | Integration guide for LOD rendering mod developers |
| [Paper](paper.md) | Paper plugin implementation and differences from Fabric |
| [Development](development.md) | Building, testing, and contributing |

## Supported Platforms

- **Fabric** (client + server) -- Minecraft 1.21.1
- **Paper** (server only) -- Minecraft 1.21.1
