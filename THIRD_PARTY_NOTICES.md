# Third-party components

MeshHome project source is licensed under **GPL-3.0-only**; see [`LICENSE`](LICENSE). This does not replace the individual notices or terms of bundled dependencies.

- **Nord Security libtelio**: GPL-3.0-only, pinned source https://github.com/NordSecurity/libtelio/tree/c15a3bd60340e9cc0da74d93fe50b3b875e9e052. Only incorporated when building the native engine. Preserve upstream license notices and satisfy corresponding-source requirements when distributing combined builds.
- **dnsjava 3.6.3**: BSD-3-Clause, https://github.com/dnsjava/dnsjava. DNS wire parsing and response serialization.
- **Bouncy Castle bcprov-jdk18on 1.86**: Bouncy Castle license (MIT-style), https://www.bouncycastle.org/licence.html. Local X25519 identity generation.
- **Kotlin standard library**: Apache-2.0, https://github.com/JetBrains/kotlin.
- **JetBrains annotations 13.0**: Apache-2.0; a Kotlin standard-library runtime dependency.
- **SLF4J API 1.7.36**: MIT license; a dnsjava runtime dependency. No SLF4J provider is configured by MeshHome.
- **Gradle wrapper**: Apache-2.0, https://github.com/gradle/gradle.

The Android release runtime dependency list was checked with Gradle's `releaseRuntimeClasspath` report. This remains an integration inventory, **not** a complete distribution license bundle: the native Cargo dependency graph and build/test tool dependencies have not been fully inventoried or reviewed. Generate/review the complete resolved dependency license inventory (including native transitive dependencies) and assemble all required notices/source materials before distributing a release. NordVPN and Meshnet names belong to their respective owners; this app is not endorsed by Nord Security.
