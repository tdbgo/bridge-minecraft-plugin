# Third-Party Notices

This inventory covers direct runtime, compile, test, and build dependencies declared by the Bridge 0.5 release line. Transitive dependencies are not copied into the plugin JAR unless stated otherwise.

| Component | Version | Use | Included in Bridge JAR | License |
| --- | --- | --- | --- | --- |
| Gson | 2.14.0 | Runtime JSON implementation | Yes, shaded | Apache License 2.0 |
| Nimbus JOSE + JWT | 10.9.1 | JWT signature and claims verification | Yes, shaded and relocated | Apache License 2.0 |
| Java-WebSocket | 1.6.0 | Optional WebSocket event server | Yes, shaded and relocated | MIT |
| Paper API | 26.2.build.111-stable | Compile-only server API | No | GPL-3.0 overall; listed Paper contributions may be MIT |
| JUnit Jupiter | 6.1.0 | Test only | No | Eclipse Public License 2.0 |
| Shadow Gradle plugin | 9.6.1 | Build only | No | Apache License 2.0 |
| Gradle Wrapper / Gradle | 9.6.1 | Build bootstrap/tooling | No plugin runtime code | Apache License 2.0 |
| Prometheus | Ubuntu package version selected at deployment | Optional external time-series collector | No | Apache License 2.0 |

## Sources

- Gson: <https://github.com/google/gson/tree/gson-parent-2.14.0>
- Nimbus JOSE + JWT: <https://connect2id.com/products/nimbus-jose-jwt>
- Java-WebSocket: <https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0>
- Paper: <https://github.com/PaperMC/Paper>
- Paper license: <https://github.com/PaperMC/Paper/blob/main/LICENSE.md>
- JUnit: <https://github.com/junit-team/junit-framework/tree/r6.1.0>
- Shadow: <https://github.com/GradleUp/shadow/tree/9.6.1>
- Gradle: <https://github.com/gradle/gradle/tree/v9.6.1>
- Prometheus: <https://github.com/prometheus/prometheus>

The Apache License 2.0 text distributed for Gson, Nimbus JOSE + JWT and Apache-licensed build files is in `LICENSES/Apache-2.0.txt`. The Java-WebSocket MIT text is in `LICENSES/MIT-Java-WebSocket.txt`. Paper, JUnit, and Prometheus are not bundled in the Bridge JAR. Their license identifiers and source locations are recorded here for source, build, and deployment provenance. The web-host installer records the exact OS package version separately at deployment time.

Before each public release, regenerate or manually verify this inventory against the resolved dependency graph. Bridge's original code is separately licensed under Apache License 2.0; see `LICENSE.md` and `NOTICE`.
