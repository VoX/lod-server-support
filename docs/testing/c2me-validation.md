# Minecraft 1.21.11 C2ME validation

The two existing pins are retained deliberately. A different pin is not evidence of incompatibility; neither is upgraded by the Astra fixes.

| Profile | C2ME version | Modrinth version ID | Consumer |
| --- | --- | --- | --- |
| regular-map | 0.4.0-alpha.0.18 | MfQIu1Y0 | test-server.sh; explicit Gradle profile |
| benchmark | 0.4.0-alpha.0.26 | 879vA5z6 | default benchmark.c2me=true Gradle arm |

Run server gametests with each resolved artifact, serially, using Java21:

```bash
./gradlew :fabric:runGameTest -Pbenchmark.c2me=true -Pbenchmark.c2meProfile=regular-map --max-workers=1
./gradlew :fabric:runGameTest -Pbenchmark.c2me=true -Pbenchmark.c2meProfile=benchmark --max-workers=1
```

The default remains benchmark. Invalid profile names fail configuration. Record loaded mod versions and exact task outcomes in the implementation ledger; a correct dependency declaration alone does not establish a live pass. Run any selected soak through its complete wrapper after harness ownership gates. Consolidation requires evidence for the chosen artifact and updating this matrix and the consumer pin together.

The opt-in dependency uses `modLocalRuntime`, so Loom remaps production intermediary names and nested access wideners into the named development runtime. Plain `localRuntime` reproduced a loader namespace failure before any gametest ran. This follows [Fabric Loom's mod dependency configuration](https://wiki.fabricmc.net/documentation:fabric_loom#dependency_configurations); neither C2ME pin nor release dependencies change.

The shared `gradle/c2me-dev-runtime.gradle` helper exposes the umbrella's declared nested jars because its Modrinth POM has no module dependencies. It retains the Java 21 profile's optional-module selection: omit incompatible Java floors and optional modules depending on those omissions, then reject any omission reaching the umbrella's required dependencies. For alpha.0.18 this omits native math and its dependent DFC module; alpha.0.26 omits native math only. Unknown Java predicates fail configuration. These are the same optional modules the production Java 21 loader cannot activate; this is not an LSS feature cut or an upgrade to Java 25.
