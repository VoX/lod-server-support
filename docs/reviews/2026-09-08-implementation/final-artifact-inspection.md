# Final CI-versioned artifact inspection

Version 0.14.0; exact filenames only. Read-only inspection; no Gradle, jar writes, deployment or live runtime claims.

| Line | Jars | Java / LSS major | LSS/VSS class + common equality | NeoForge stock SQLite + Zstd equality | Result |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | 6 | 21 / platform 65, common 65 | PASS | PASS | PASS |
| 1.21.10 | 6 | 21 / platform 65, common 65 | PASS | PASS | PASS |
| 1.21.11 | 6 | 21 / platform 65, common 65 | PASS | PASS | PASS |
| 26.1 | 6 | 25 / platform 69, common 65 | PASS | PASS | PASS |
| 26.2 | 6 | 25 / platform 69, common 65 | PASS | PASS | PASS |

The JSON records exact paths, SHA-256, sizes, build mtimes, manifests, metadata, mapping namespaces, class-major distributions, nested-jar hashes and cached Maven stock match paths. LSS class-major checks include Fabric nested common code. Common is deliberately compiled with release 21 (major 65), including on Java 25 lines; platform code uses the line target. Third-party library class majors are recorded separately.

Only lines explicitly passed after parent build completion are inspected. Maintained nonshipping NeoForge jars are inspected too. HEAD at inspection is context, not proof that a later source/doc commit was included in a previously built jar.
