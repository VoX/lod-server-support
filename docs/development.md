# Development

This page covers building, testing, and contributing to the project.

## Prerequisites

- Java 21+
- Git

No Minecraft installation is required. Gradle downloads all dependencies automatically.

## Project Structure

```
lod-server-support/
├── common/           Pure Java utilities (no MC deps)
│   └── src/main/java/dev/vox/lss/common/
├── fabric/           Fabric mod (client + server)
│   ├── src/main/java/dev/vox/lss/
│   ├── src/test/java/dev/vox/lss/          (Tier 1: JUnit)
│   └── src/gametest/java/dev/vox/lss/test/ (Tier 2+3: gametests)
├── paper/            Paper plugin (server only)
│   └── src/main/java/dev/vox/lss/paper/
├── build.gradle
├── settings.gradle
└── CLAUDE.md
```

The `common` module is included in both `fabric` and `paper` via Gradle project dependency. Fabric uses `include project(':common')` to embed it in the mod JAR. Paper uses the shadow plugin to bundle it.

## Build Commands

```bash
# Build Fabric mod + run Tier 1 & 2 tests
./gradlew :fabric:build -x runClientGameTest

# Build Paper plugin (shadow JAR, no tests)
./gradlew :paper:shadowJar

# Clean all build artifacts
./gradlew clean
```

### Output JARs

| Module | Path | Contents |
|--------|------|----------|
| Fabric | `fabric/build/libs/lod-server-support-fabric-*.jar` | Fabric mod (client + server) |
| Paper | `paper/build/libs/lod-server-support-paper-all.jar` | Shadow JAR with all deps (excludes SLF4J) |

## Test Architecture

Tests exist only in the Fabric module. Paper is compile-only (manual testing required).

### Tier 1: JUnit Unit Tests

```bash
./gradlew :fabric:test -x runGameTest -x runClientGameTest
```

54 JUnit 5 tests via `fabric-loader-junit`. Runs in ~7 seconds.

**What's tested:**
- Position packing (`PositionUtil`)
- Bandwidth limiter token bucket (`SharedBandwidthLimiter`)
- Chunk change tracker (`ChunkChangeTracker`)
- Config validation and clamping
- Column cache store binary format
- All 8 payload codecs (encode/decode roundtrips)
- Zstd compression roundtrips for section data

### Tier 2: Server Gametests

```bash
./gradlew :fabric:runGameTest
```

Server-side Fabric gametests that start a real dedicated Minecraft server. Runs in ~13 seconds.

**What's tested:**
- `RequestProcessingService` activates on dedicated server
- Diagnostics return valid metrics
- Command registration (`/lsslod`)
- Config loaded with valid values
- Disk reader and generation service created when enabled
- Bandwidth usage is zero with no players
- `ChunkChangeTracker` drain clears state

Uses the `@GameTest` annotation from `net.fabricmc.fabric.api.gametest.v1` with `structure = "fabric-gametest-api-v1:empty"`.

### Tier 3: Client Gametests

```bash
./gradlew :fabric:runClientGameTest
```

End-to-end client-server flow test. Starts an integrated server + client in headless mode (Fabric Loom handles headless rendering automatically).

**What's tested:**
- Handshake and session config exchange
- Spiral scan progress (batches sent, positions requested)
- Batch lifecycle (some batches complete)
- Timestamp tracking (received column count > 0)
- Bandwidth bounded correctly
- No pending column leaks after settling
- Generation budget active when generation enabled

The test waits 40 ticks for handshake setup, 200 ticks for processing, and 100 ticks for settling before final assertions.

### Combined Commands

```bash
# Tier 1 + 2 combined
./gradlew :fabric:test -x runClientGameTest

# Full build + Tier 1 + 2
./gradlew :fabric:build -x runClientGameTest
```

Note: `./gradlew :fabric:test` runs both JUnit and server gametests because Fabric's `configureTests` hooks them together.

## System Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `-Dlss.test.integratedServer=true` | `false` | Allow `RequestProcessingService` to activate on integrated servers (for testing) |
| `-Dfabric.client.gametest.disableNetworkSynchronizer=true` | `false` | Disable network sync for client gametests |

Both are set automatically in `fabric/build.gradle` for gametest run configurations.

## Mappings

The project uses **Mojang official mappings** (not Yarn). Both Fabric (via Loom `officialMojangMappings()`) and Paper (via paperweight-userdev) use Mojang mappings natively.

When referencing Minecraft internals, use Mojang names (e.g., `LevelChunkSection`, `ChunkMap`, `ServerChunkCache`).

## Key Patterns

### Payload Pattern

Fabric payloads are records implementing `CustomPacketPayload` with a static `CODEC` field:

```java
// fabric/src/main/java/dev/vox/lss/networking/payloads/HandshakeC2SPayload.java
public record HandshakeC2SPayload(int protocolVersion) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HandshakeC2SPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.parse(LSSConstants.CHANNEL_HANDSHAKE));

    public static final StreamCodec<FriendlyByteBuf, HandshakeC2SPayload> CODEC =
        StreamCodec.of(HandshakeC2SPayload::encode, HandshakeC2SPayload::decode);

    // encode/decode methods write/read fields to/from FriendlyByteBuf
}
```

Paper uses manual encoding in `PaperPayloadHandler` -- same field order and types, identical wire bytes.

### Thread Safety Pattern

- `ConcurrentHashMap` for player state maps
- `ConcurrentLinkedQueue` for disk reader results (producer: thread pool, consumer: server thread)
- `volatile` for cross-thread state flags (`hasHandshake`, `serverEnabled`)
- `AtomicLong` / `AtomicInteger` for diagnostic counters
- `CopyOnWriteArrayList` for API consumer list
- `synchronized` on `ChunkChangeTracker` (static state accessed from chunk save threads)

### Config Pattern

```java
public class LSSServerConfig extends JsonConfig {
    public boolean enabled = true;
    public int lodDistanceChunks = 128;
    // ... fields with defaults

    @Override
    protected void validate() {
        lodDistanceChunks = clamp(lodDistanceChunks, 1, 512);
        // ... clamp all fields
    }
}
```

Fields are public with inline defaults. `validate()` clamps to safe ranges. Load-validate-save-back ensures the file always matches active values.

### Compatibility Pattern

All optional mod integration uses runtime reflection:

```java
// In ModCompat.init():
if (FabricLoader.getInstance().isModLoaded("target-mod")) {
    TargetModCompat.init();  // Isolated class prevents ClassNotFoundError
}

// In TargetModCompat.init():
Class<?> cls = Class.forName("com.example.TargetClass");
MethodHandle method = lookup.findStatic(cls, "methodName", ...);
LSSApi.registerConsumer((level, dim, section, ...) -> {
    method.invoke(...);
});
```

Each compat class is isolated so that `Class.forName()` failure (mod not installed) is caught cleanly without affecting other integrations.
