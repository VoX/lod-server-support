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
