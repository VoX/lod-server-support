# Upstream issue draft — DrexHD/AntiXray

Ready to file at https://github.com/DrexHD/AntiXray/issues (not yet filed). Paste a live
stack trace from a `./test-server.sh run-fabric-antixray` run (pre-shim LSS build) into the
marked spot before filing. Title suggestion:

> Unbound `ScopedValue.get()` in PalettedContainer mixins crashes mods that serialize
> sections outside chunk packets

---

Hi — thanks for AntiXray. We (LOD Server Support, https://github.com/VoX/lod-server-support)
received reports of servers crashing when running LSS alongside AntiXray, and traced it to a
small unguarded read in two mixins. Filing because it affects any mod that calls
`LevelChunkSection.write(FriendlyByteBuf)` / `PalettedContainer.write` outside vanilla
chunk-packet construction — LOD/distant-terrain servers, map mods, custom chunk sync.

**Mechanism** (branch `26.1`, `fabric-1.4.16+26.1`):

- `PalettedContainer$DataMixin.initializeChunkPacketInfo` (wrapping `BitStorage.getRaw()`
  inside `Data.write`) and `PalettedContainerMixin.setPresetValues` (injected after
  `Data.write` in `PalettedContainer.write`) both read
  `Arguments.PACKET_INFO.get()` / `Arguments.CHUNK_SECTION_INDEX.get()` with raw `.get()`.
- Those ScopedValues are bound only inside `ClientboundLevelChunkWithLightPacket` →
  `extractChunkData`. Any other caller of `PalettedContainer.write` reaches the mixins with
  the values unbound, and an unbound `ScopedValue.get()` throws `NoSuchElementException`.
- In LSS's case the write runs on the server tick (serializing loaded sections for LOD
  streaming), so the exception propagates out of the tick handler and kills the server.

```
<live stack trace here>
```

**Suggested fix** — one line per site, preserving the existing null guard:

```java
ChunkPacketInfo<BlockState> chunkPacketInfo = Arguments.PACKET_INFO.orElse(null);
Integer chunkSectionIndex = Arguments.CHUNK_SECTION_INDEX.orElse(null);
```

`orElse(null)` routes out-of-band writes onto the same skip path the biome write already
uses (`LevelChunkSectionMixin` binds `PACKET_INFO` to null there), so obfuscation behavior
inside real packet construction is unchanged. The `Arguments` javadoc already anticipates
values being present "unexpectedly" — this is the inverse case (absent unexpectedly).

Happy to send the PR if you'd take it.

For our side: current LSS builds work around it by binding the four `Arguments` ScopedValues
to null around our own serialization (so released AntiXray versions don't crash LSS servers),
but other section-serializing mods will keep hitting this until the reads are guarded.
