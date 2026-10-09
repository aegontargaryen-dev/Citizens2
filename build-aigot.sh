#!/usr/bin/env bash
# Build locally only. Never downloads, builds, publishes, or deploys private AIgot.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
API_REVISION=e19598137d192935117d013f1674d10ca3a971bf
UPSTREAM_REVISION=2c0c7bf8a0dc11c25b6dfc63966769c074990b18
PROTOCOLLIB_SHA256=41d7d8e99e21eebd343c04c07e6b5afff79cad28240afe1dbad0cce52402d2a6

usage() {
    cat <<'EOF'
Usage: AIGOT_JAR=/absolute/path/to/prebuilt/AIgot.jar JAVA_HOME=/path/to/jdk21 JAVA8_HOME=/path/to/java8 ./build-aigot.sh [--check]

Required:
  AIGOT_JAR             Genuine, privately supplied, fully packaged AIgot 1.8.8 JAR
  JAVA_HOME             Full JDK 21 (java, javac, lib/ct.sym)
  JAVA8_HOME            Java 8 runtime for adapter tests (API tests still use JDK 21)

Optional:
  MAVEN                 Maven executable or wrapper path (default: mvn; Maven 3.9+)
  MAVEN_SETTINGS        Maven settings XML for proxies/repository credentials
  CITIZENS_API_SOURCE   Local Git clone or URL (default: official CitizensAPI GitHub)
  PROTOCOLLIB_JAR       Local official ProtocolLib 5.0.0 JAR; checksum is mandatory
  AIGOT_REVISION        User-reported private AIgot revision for build provenance
  AIGOT_SHA256          Expected SHA-256 for the private input (if pinned)
  AIGOT_BUILD_WORK      Existing EMPTY directory outside this checkout for build inputs/cache

--check verifies prerequisites and input JAR structure without network access/builds.
Normal builds use an isolated temporary Maven repository, rebuild the exact API
source, run the reactor tests, assemble and verify the Java 8 plugin. Work/cache
is retained outside the checkout for inspection. No server is started or deployed.
EOF
}

fail() { printf 'AIgot build: %s\n' "$*" >&2; exit 1; }
CHECK_ONLY=false
case "${1:-}" in
    -h|--help) usage; exit 0 ;;
    --check) CHECK_ONLY=true ;;
    "") ;;
    *) usage >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || fail "Unexpected arguments; use --help"
[[ -n "${AIGOT_JAR:-}" ]] || fail "Set AIGOT_JAR to your genuine, prebuilt private AIgot server JAR. It is never fetched or substituted."
[[ -f "$AIGOT_JAR" ]] || fail "AIGOT_JAR does not exist: $AIGOT_JAR"
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" && -x "$JAVA_HOME/bin/javap" && -f "$JAVA_HOME/lib/ct.sym" ]] || fail "JAVA_HOME must point to a full JDK 21, not a JRE"
export PATH="$JAVA_HOME/bin:$PATH"
[[ "$("$JAVA_HOME/bin/javac" -version 2>&1)" == 'javac 21'* ]] || fail "Use JDK 21; generated plugin bytecode still targets Java 8"
for tool in git python3; do command -v "$tool" >/dev/null || fail "Missing prerequisite: $tool"; done
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' || fail "Python 3.9+ is required"
MAVEN="${MAVEN:-mvn}"
command -v "$MAVEN" >/dev/null || fail "Maven is missing; set MAVEN to Maven 3.9+ or a Maven wrapper"
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then
    [[ -f "$MAVEN_SETTINGS" ]] || fail "MAVEN_SETTINGS does not exist"
fi
[[ -n "${JAVA8_HOME:-}" && -x "$JAVA8_HOME/bin/java" ]] || fail "Set JAVA8_HOME to the Java 8 runtime required by the native adapter tests"
"$JAVA8_HOME/bin/java" -version 2>&1 | grep -q 'version "1.8\.' || fail "JAVA8_HOME must point to Java 8"
git -C "$ROOT" merge-base --is-ancestor "$UPSTREAM_REVISION" HEAD || fail "Checkout must descend from Citizens2 $UPSTREAM_REVISION"
if [[ -n "${AIGOT_SHA256:-}" ]]; then
    python3 "$ROOT/scripts/aigot/verify_build.py" --check-sha256 "$AIGOT_JAR" "$AIGOT_SHA256"
fi
python3 "$ROOT/scripts/aigot/verify_build.py" --check-input "$AIGOT_JAR"
if [[ -n "${PROTOCOLLIB_JAR:-}" ]]; then
    python3 "$ROOT/scripts/aigot/verify_build.py" --check-sha256 "$PROTOCOLLIB_JAR" "$PROTOCOLLIB_SHA256"
fi
if "$CHECK_ONLY"; then
    printf 'Prerequisites and input structure checked; no build or runtime compatibility claim.\n'
    exit 0
fi

if [[ -n "${AIGOT_BUILD_WORK:-}" ]]; then
    [[ -d "$AIGOT_BUILD_WORK" ]] || fail "AIGOT_BUILD_WORK must be an existing empty directory"
    WORK="$(cd -- "$AIGOT_BUILD_WORK" && pwd)"
    [[ -z "$(find "$WORK" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail "AIGOT_BUILD_WORK must be empty to preserve earlier build inputs"
else
    WORK="$(mktemp -d "${TMPDIR:-/tmp}/citizens-aigot.XXXXXXXX")"
fi
case "$WORK/" in "$ROOT/"*) fail "Build work/cache must be outside the public checkout" ;; esac
printf 'Private build inputs and isolated Maven cache: %s\n' "$WORK"
# Do not let inherited MAVEN_ARGS silently add goals/profiles or change repositories.
unset MAVEN_ARGS
MVN=("$MAVEN" --batch-mode --no-transfer-progress "-Dmaven.repo.local=$WORK/repository")
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then MVN+=(-s "$MAVEN_SETTINGS"); fi

"$MAVEN" --version > "$WORK/maven-version.txt"
grep -Eq 'Apache Maven 3\.(9|[1-9][0-9])\.' "$WORK/maven-version.txt" || fail "Maven 3.9+ is required (this recipe is not tested with Maven 4)"
"$JAVA_HOME/bin/java" -version 2> "$WORK/java-version.txt"
python3 - "$AIGOT_JAR" "$WORK/AIgot.jar" <<'PY'
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])
PY
if [[ -n "${AIGOT_SHA256:-}" ]]; then
    python3 "$ROOT/scripts/aigot/verify_build.py" --check-sha256 "$WORK/AIgot.jar" "$AIGOT_SHA256"
fi

git init -q "$WORK/api"
git -C "$WORK/api" remote add origin "${CITIZENS_API_SOURCE:-https://github.com/CitizensDev/CitizensAPI.git}"
git -C "$WORK/api" fetch --depth 1 origin "$API_REVISION"
git -C "$WORK/api" checkout -q --detach FETCH_HEAD
python3 "$ROOT/scripts/aigot/prepare_api.py" "$WORK/api"

if [[ -n "${PROTOCOLLIB_JAR:-}" ]]; then
    python3 - "$PROTOCOLLIB_JAR" "$WORK/ProtocolLib.jar" <<'PY'
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])
PY
else
    command -v curl >/dev/null || fail "Install curl or supply PROTOCOLLIB_JAR"
    curl --fail --location --retry 2 \
        https://github.com/dmulloy2/ProtocolLib/releases/download/5.0.0/ProtocolLib.jar \
        --output "$WORK/ProtocolLib.jar"
fi
python3 "$ROOT/scripts/aigot/verify_build.py" --check-sha256 "$WORK/ProtocolLib.jar" "$PROTOCOLLIB_SHA256"

# Generated minimal POMs are local build metadata, not original publisher POMs.
for spec in 'AIgot.jar:net.aigot:aigot-server:1.8.8-R0.1-SNAPSHOT' \
            'ProtocolLib.jar:com.comphenix.protocol:ProtocolLib:5.0.0'; do
    IFS=: read -r file group artifact version <<< "$spec"
    "${MVN[@]}" -f "$ROOT/pom-aigot.xml" --non-recursive \
        org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
        "-Dfile=$WORK/$file" "-DgroupId=$group" "-DartifactId=$artifact" \
        "-Dversion=$version" -Dpackaging=jar -DgeneratePom=true
done
"${MVN[@]}" -f "$WORK/api/pom-aigot.xml" clean install
"${MVN[@]}" -f "$ROOT/pom-aigot.xml" "-Daigot.test.jvm=$JAVA8_HOME/bin/java" clean verify

JAR="$ROOT/dist/target/aigot/Citizens-2.0.35-aigot-1.jar"
python3 "$ROOT/scripts/aigot/verify_build.py" "$JAR" "$WORK/AIgot.jar" "$ROOT" \
    "$WORK/api/target/aigot/citizensapi-2.0.35-aigot-api-e1959813-1.jar" \
    "$WORK/java-version.txt" "$WORK/maven-version.txt"
printf '\nBuilt and statically verified: %s\n' "$JAR"
printf 'Provenance: %s.build.json\n' "$JAR"
printf 'Runtime compatibility requires separate isolated AIgot gameplay validation.\n'
