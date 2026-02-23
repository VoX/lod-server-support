# API Guide

This page describes how LOD rendering mods integrate with LSS to receive deserialized chunk sections.

## Quick Start

Register a `ChunkSectionConsumer` during mod initialization:

```java
// In your mod's ClientModInitializer.onInitializeClient():
LSSApi.registerConsumer((level, dimension, section, chunkX, sectionY, chunkZ, blockLight, skyLight) -> {
    // section contains deserialized block states and biomes
    // blockLight and skyLight may be null if the server has sendLightData=false
    myLodRenderer.ingestSection(dimension, section, chunkX, sectionY, chunkZ, blockLight, skyLight);
});
```

## ChunkSectionConsumer

```java
// fabric/src/main/java/dev/vox/lss/api/ChunkSectionConsumer.java

@FunctionalInterface
public interface ChunkSectionConsumer {
    void onSectionReceived(
        ClientLevel level,
        ResourceKey<Level> dimension,
        LevelChunkSection section,
        int chunkX,
        int sectionY,
        int chunkZ,
        DataLayer blockLight,
        DataLayer skyLight
    );
}
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `level` | `ClientLevel` | Current client level instance |
| `dimension` | `ResourceKey<Level>` | Dimension key (e.g., `Level.OVERWORLD`, `Level.NETHER`) |
| `section` | `LevelChunkSection` | Deserialized section with block state and biome palettes |
| `chunkX` | `int` | Chunk X coordinate |
| `sectionY` | `int` | Section Y index (vertical, e.g., -4 to 19 for overworld) |
| `chunkZ` | `int` | Chunk Z coordinate |
| `blockLight` | `DataLayer` | 2048-byte block light data, or `null` if not sent |
| `skyLight` | `DataLayer` | 2048-byte sky light data, or `null` if not sent |

### Threading Guarantees

- `onSectionReceived` is called on the **client main thread** (Minecraft render thread)
- The consumer list is a `CopyOnWriteArrayList`, so registration and removal are safe from any thread
- Exceptions thrown by a consumer are caught and logged by `LSSApi.dispatch()` -- one failing consumer does not affect others

## LSSApi

```java
// fabric/src/main/java/dev/vox/lss/api/LSSApi.java

public class LSSApi {
    // Registration
    public static void registerConsumer(ChunkSectionConsumer consumer);
    public static void removeConsumer(ChunkSectionConsumer consumer);

    // Server state queries
    public static boolean isServerEnabled();
    public static int getServerLodDistance();
}
```

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `registerConsumer(consumer)` | void | Register a consumer. Call during mod init. |
| `removeConsumer(consumer)` | void | Remove a previously registered consumer. |
| `isServerEnabled()` | boolean | Whether the connected server has LSS enabled and protocol version matches. |
| `getServerLodDistance()` | int | Server's configured LOD distance in chunks, or 0 if not connected. |

## Zero-Dependency Integration

If you want to support LSS as an optional dependency (no compile-time JAR required), use `MethodHandle` reflection:

```java
public class LSSCompat {
    private static MethodHandle registerConsumer;

    public static boolean init() {
        try {
            var lookup = MethodHandles.lookup();
            Class<?> apiClass = Class.forName("dev.vox.lss.api.LSSApi");
            Class<?> consumerClass = Class.forName("dev.vox.lss.api.ChunkSectionConsumer");

            registerConsumer = lookup.findStatic(
                apiClass, "registerConsumer",
                MethodType.methodType(void.class, consumerClass)
            );

            // Create a proxy that implements ChunkSectionConsumer
            Object consumer = Proxy.newProxyInstance(
                consumerClass.getClassLoader(),
                new Class<?>[] { consumerClass },
                (proxy, method, args) -> {
                    if ("onSectionReceived".equals(method.getName())) {
                        // args: level, dimension, section, cx, sY, cz, blockLight, skyLight
                        handleSection(args);
                    }
                    return null;
                }
            );

            registerConsumer.invoke(consumer);
            return true;
        } catch (ClassNotFoundException e) {
            return false; // LSS not installed
        } catch (Throwable e) {
            LOG.error("Failed to initialize LSS compat", e);
            return false;
        }
    }
}
```

Gate the initialization on mod presence:

```java
// In your ClientModInitializer:
if (FabricLoader.getInstance().isModLoaded("lod-server-support")) {
    LSSCompat.init();
}
```

## VoxyCompat Reference

LSS includes a working example of zero-dependency integration with Voxy:

```java
// fabric/src/main/java/dev/vox/lss/compat/VoxyCompat.java (simplified)

public class VoxyCompat {
    public static boolean init() {
        try {
            var lookup = MethodHandles.lookup();

            // Resolve Voxy classes
            Class<?> worldIdClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            Class<?> ingestClass  = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");

            // Resolve methods
            MethodHandle worldIdOf = lookup.findStatic(worldIdClass, "of", ...);
            MethodHandle rawIngest = lookup.findStatic(ingestClass, "rawIngest", ...);

            // Register consumer
            LSSApi.registerConsumer((level, dim, section, cx, sY, cz, bl, sl) -> {
                Object worldId = worldIdOf.invoke(level);
                rawIngest.invoke(worldId, section, cx, sY, cz, bl, sl);
            });

            return true;
        } catch (ClassNotFoundException e) {
            return false; // Voxy not installed
        }
    }
}
```

Key points:
- Voxy classes are resolved entirely via `Class.forName()` and `MethodHandles`
- No Voxy JAR needed at compile time
- `ClassNotFoundException` is the expected path when Voxy isn't installed
- The `ChunkSectionConsumer` lambda captures the resolved method handles

## Dependency Declaration

### Hard Dependency (fabric.mod.json)

If your mod requires LSS:

```json
{
  "depends": {
    "lod-server-support": ">=1.0.0"
  }
}
```

Add LSS as a compile dependency in `build.gradle`:

```gradle
dependencies {
    modImplementation "dev.vox:lod-server-support-fabric:1.0.0"
}
```

### Optional Dependency

If your mod works with or without LSS:

```json
{
  "suggests": {
    "lod-server-support": ">=1.0.0"
  }
}
```

Use the zero-dependency `MethodHandle` pattern above, gated by `FabricLoader.getInstance().isModLoaded("lod-server-support")`.
