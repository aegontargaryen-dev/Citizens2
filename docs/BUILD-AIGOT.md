# Citizens for AIgot: isolated Java 8 build

This is a separately labelled, AIgot-only Citizens port. It is based on Citizens2
[`2c0c7bf8a0dc11c25b6dfc63966769c074990b18`](https://github.com/CitizensDev/Citizens2/commit/2c0c7bf8a0dc11c25b6dfc63966769c074990b18)
(the source of upstream 2.0.35 build 3478), with CitizensAPI pinned to
[`e19598137d192935117d013f1674d10ca3a971bf`](https://github.com/CitizensDev/CitizensAPI/commit/e19598137d192935117d013f1674d10ca3a971bf).
It is not an official Citizens distribution or a byte-identical rebuild of 3478.

The private AIgot server is a **user-supplied build input**. Matching Minecraft
`1.8.8`/`v1_8_R3` is insufficient: the upstream adapter is not binary-compatible
with the modified server. The native compatibility hooks required by the port
must be present in the supplied AIgot build. Compilation/static verification
does not establish NPC gameplay correctness. Do not deploy this output until
the matching Citizens/AIgot pair passes isolated runtime validation.

## Prerequisites and build

- A full JDK 21, including `javac` and `lib/ct.sym`; a JRE cannot compile this build
- A Java 8 runtime for the native adapter regression tests
- Maven 3.9+ (or a Maven 3.9+ wrapper), Git, Python 3.9+, and curl
- A genuine, fully packaged AIgot 1.8.8 server JAR that you built privately
- Access to the public dependency repositories listed in the POMs

```sh
git clone --branch port/aigot-1.8.8 https://github.com/aegontargaryen-dev/Citizens2.git
cd Citizens2
# Pin the final reviewed port commit rather than relying on the mutable branch:
# git checkout --detach <reviewed-port-commit>
# Record that exact commit together with your private AIgot commit.

export JAVA_HOME=/absolute/path/to/jdk-21
# Required: run native adapter regression tests on the actual target JVM
export JAVA8_HOME=/absolute/path/to/java-8
export AIGOT_JAR=/absolute/private/path/to/AIgot.jar
export AIGOT_REVISION=your-private-server-commit # provenance label, not verification
# Optional: require an independently recorded checksum of that exact private JAR
# export AIGOT_SHA256=the-64-character-sha256
# Optional if mvn is not on PATH:
export MAVEN=/absolute/path/to/apache-maven-3.9.11/bin/mvn
# Optional proxy/credential configuration:
# export MAVEN_SETTINGS=/absolute/private/path/to/settings.xml

./build-aigot.sh --check
./build-aigot.sh
```

`--check` checks local prerequisite/input structure and the public signatures of
`World.setPlayerListMembership(EntityHuman, boolean)`,
`World.canSeeSkyIfLoaded(BlockPosition)`, and
`EntityTracker.replaceEntry(EntityTrackerEntry, EntityTrackerEntry)`. It does not
prove that the JAR is authentic, implements those hooks correctly, or works at runtime.
Missing AIgot input fails before dependency downloads. No AIgot source, stub,
alternate server, remote binary mirror, or private artifact is downloaded.

The script rebuilds the exact API revision from its official Git source, verifies
the genuine [ProtocolLib 5.0.0 release](https://github.com/dmulloy2/ProtocolLib/releases/tag/5.0.0)
against SHA-256
`41d7d8e99e21eebd343c04c07e6b5afff79cad28240afe1dbad0cce52402d2a6`,
and runs `clean verify` for main, a test-only library helper, the 1.8 adapter and
the distribution. Tests are
enabled. A build failure is not bypassed by silently disabling behavior or tests.

For an already obtained, checksum-matching official ProtocolLib JAR, set
`PROTOCOLLIB_JAR=/absolute/path/to/ProtocolLib.jar`. To avoid downloading API Git
objects again, set `CITIZENS_API_SOURCE=/absolute/path/to/CitizensAPI`: the script
fetches the pinned commit into a fresh checkout and never uses your dirty files.
Other Maven dependencies may still require network access.

`JAVA8_HOME` is required and selects a separate Java 8 JVM for adapter tests; it does not change
the JDK 21 compiler or the API test JVM. With direct Maven use, the equivalent is
`-Daigot.test.jvm=/absolute/path/to/java-8/bin/java`. The legacy native server's
Log4j bootstrap fails on JDK 21; running these tests on the build JDK is not a
supported substitute. The wrapper checks Java 8 before downloading dependencies.
Adapter test forks default to a 256 MiB heap; `-Daigot.test.jvm.args` can override it.

## Output and isolation

The complete plugin is:

```text
dist/target/aigot/Citizens-2.0.35-aigot-1.jar
```

The adjacent `.sha256` and `.build.json` record the produced checksum, public
source pins, the actual build source digest, input JAR checksums, tool versions,
adapter list and static checks. The private AIgot revision label is user-reported;
only its input bytes are independently hashed. Preserve the private input and
these records together when reproducing or reviewing a build.

- Maven targets use each module's ignored `target/aigot` directory
- The input server, ProtocolLib, API checkout and Maven repository stay in a new
  temporary directory **outside this checkout**; its location is printed and it
  is retained for inspection. Delete it yourself when no longer needed
- `AIGOT_BUILD_WORK` can select an existing empty directory outside the checkout
- The script installs only inputs/API into that isolated repository, then packages
  the reactor. It does not install the port into your normal Maven repository,
  publish any artifact, start a server, or copy anything into a plugins directory
- The original upstream multi-version POMs are untouched
- The private input and its generated minimal POM must never be committed,
  uploaded, published, or placed in this public fork

All port modules use `2.0.35-aigot-1`. The unmodified API sources are rebuilt as
`net.citizensnpcs:citizensapi:2.0.35-aigot-api-e1959813-1`, deliberately different
from official coordinates and earlier `2.0.35-source-e1959813` artifacts. The
locally installed server uses `net.aigot:aigot-server:1.8.8-R0.1-SNAPSHOT` with a
generated input-only POM; this is not represented as the server's original POM.
The same distinction applies to the checksum-verified ProtocolLib local POM.

The wrapper does not accept arbitrary Maven goals. Deployment is disabled in
the isolated POMs. Direct POM use is intended only for developers who have
prepared the same exact inputs and understand Maven repository isolation.

## Why JDK 21 produces a Java 8 plugin

The upstream main/API source uses modern build-time Spigot types while retaining
Java 8 source compatibility. The compiler is JDK 21 with `--release 8`. The build
uses the pinned Spigot API `1.21-R0.1-20240807.214924-87`, with Mojang authlib
`6.0.54`, Netty transport `4.1.97.Final`, Log4j core `2.22.1`, and WorldGuard `7.0.4`
as **provided compile dependencies**, not bundled runtime replacements.
The transitive WorldEdit inputs are fixed to `7.2.0-20201102.221009-187`
(Bukkit), `7.2.0-20201102.221009-185` (core), and
`7.2.0-20201102.221009-188` (worldedit-libs core). VaultAPI's redundant, mutable
legacy Bukkit dependency is excluded in favor of the pinned Spigot API. CitizensAPI
explicitly declares its directly imported json-simple 1.1.1 as provided, instead
of depending on that obsolete Bukkit dependency to supply it accidentally.

The main JAR includes the real, source-built CitizensAPI and upstream shaded
Libby implementation. The adapter compiles against the genuine provided AIgot
server. The distribution unpacks only `citizens-main` and `citizens-v1_8_R3`;
no other adapter or server dependency is assembled.

Adapter tests use the actual shaded main JAR. A tiny helper module relocates
the genuine public Adventure API 4.17.0 and its key/examination dependencies to
`clib.net.kyori`, matching the namespace used by Citizens' runtime loader. The
helper is a test-scoped dependency only, contains no private server code or
fabricated API classes, and is never part of the final distribution. Testing
with an unshaded CitizensAPI JAR first would mask the packaged main's behavior.

The verification script checks every bundled `.class` for major version 52,
requires the plugin/API/adapter entry points and the explicit AIgot version in
`plugin.yml`, rejects other NMS adapters, and rejects any class also present in
the private server JAR or under server/API package names. The rebuilt API is
checked separately for Java 8 classfiles. These are packaging checks, not proof
that optional modern API paths will be usable on Minecraft 1.8.
The verifier also requires nonempty, passing, unskipped Surefire reports for all
adapter `*Test` suites and verifies their recorded `java.version` is Java 8.
This confirms actual target-JVM regression execution rather than inferring it
from classfile versions.
The recorded test classpath must use the packaged shaded main JAR; unshaded main
classes or a standalone CitizensAPI ahead of that JAR are rejected.
It rejects both the helper libraries and adapter test classes if they appear
in the final plugin.

## Native behavior preserved by the port

NPC controllers inherit AIgot's native knockback calculation and boolean result.
The main listener forwards the original Paper event once to NPCKnockbackEvent,
preserving cancellation and edited impulse vectors; it does not calculate a
second synthetic impulse or add pass-through overrides to every entity type.
Tracker replacement uses the private server's index-aware hook, while viewer
link/unlink events retain the native identity map and live key-set. Goal removal
uses the server lifecycle so active goals are stopped rather than only erased
from a collection. Navigation consults loaded-only sky visibility.

## Reproducibility and validation limits

The complete plugin JAR is **not an offline, self-contained runtime**. The
unchanged `Citizens.loadMavenLibraries()` uses Libby at startup to download
pinned public libraries from Maven Central and relocate packages where needed:
Adventure and examination, Trove 3.0.3, PH-tree 2.8.0, and JOML 1.10.5 when the
server does not already provide it. The first isolated server start therefore
needs outbound repository access or a correctly populated Libby cache. The
production loader is unchanged; the test-only helper does not exercise that
download/startup flow. An actual plugin/server startup has not been validated
by this build recipe.

Source revisions, direct dependency/plugin versions, ProtocolLib bytes and archive
timestamps are pinned. The server is intentionally private and supplied by the
builder: two builds with different AIgot input checksums are different builds.
This is a repeatable source-build recipe, not a claim of fully hermetic or
byte-for-byte reproducibility across all JDKs, Maven versions, repositories,
transitive dependency changes or dirty source trees. Use the same toolchain,
private input and dependency cache and build a clean reviewed fork commit for
stronger reproducibility.

Before deployment, test the exact pair in an isolated Java 8 AIgot server:
NPC spawn/despawn, damage/knockback cancellation and explosions, player-list
membership, tracker replacement, chunk transitions, visibility/hide-show,
reconnect, packet NPCs, navigation without unwanted chunk loads and shutdown.
The wrapper deliberately does not start that server or treat successful
compilation as a completed runtime port.

## Attribution and packaged licenses

The original Citizens `LICENSE` is retained. The plugin also includes the
unmodified CitizensAPI license, the Libby 1.1.5 MIT license, and
`META-INF/THIRD-PARTY-NOTICES-AIGOT.txt`, and `MODIFICATIONS-AIGOT.txt`, which
identifies this modified fork and links the public source. The verifier requires
these files and checks their contents.
The Libby license was taken from its version-1.1.5 source revision
[`6d052bbacebc1b3380ee621b723253eef49a4dd3`](https://github.com/AlessioDP/libby/blob/6d052bbacebc1b3380ee621b723253eef49a4dd3/LICENSE).
Preserve the source and license notices when distributing the port, and keep the
matching public source revision available alongside any released binary.
