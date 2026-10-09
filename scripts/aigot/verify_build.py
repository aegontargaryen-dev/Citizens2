#!/usr/bin/env python3
"""Verify distribution contents/Java 8 bytecode; this is not a server test."""
import collections
import hashlib
import json
import os
import pathlib
import re
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

UPSTREAM_REVISION = "2c0c7bf8a0dc11c25b6dfc63966769c074990b18"
API_REVISION = "e19598137d192935117d013f1674d10ca3a971bf"
VERSION = "2.0.35-aigot-1"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_input(path):
    """Validate useful structure, without claiming private-input authenticity."""
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        required = {
            "net/minecraft/server/v1_8_R3/EntityLiving.class",
            "net/minecraft/server/v1_8_R3/EntityTracker.class",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftPlayer.class",
            "org/bukkit/Bukkit.class",
        }
        missing = required - names
        if missing:
            raise ValueError("AIgot input must be a fully packaged 1.8.8 server JAR; missing: "
                             + ", ".join(sorted(missing)))
    # javap inspects class bytes without initializing or running private server code.
    nms = "net/minecraft/server/v1_8_R3/"
    javap = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/javap" if "JAVA_HOME" in os.environ else "javap"
    contracts = {
        "World": {
            "setPlayerListMembership": "(L" + nms + "EntityHuman;Z)V",
            "canSeeSkyIfLoaded": "(L" + nms + "BlockPosition;)Z",
        },
        "EntityTracker": {
            "replaceEntry": "(L" + nms + "EntityTrackerEntry;L" + nms + "EntityTrackerEntry;)Z",
        },
    }
    for class_name, methods in contracts.items():
        result = subprocess.run(
            [str(javap), "-classpath", str(path), "-public", "-s", nms.replace("/", ".") + class_name],
            text=True, capture_output=True, check=True,
        )
        for name, descriptor in methods.items():
            pattern = r"public (?!static )[^\n]* " + re.escape(name) + r"\([^\n]*\);\s+descriptor: " + re.escape(descriptor)
            if not re.search(pattern, result.stdout):
                raise ValueError("Private AIgot companion hook is missing: " + class_name + "." + name
                                 + ". Supply the matching rebuilt server; the baseline server is not compatible.")
    print("Prebuilt server structure/native-hook signatures checked; SHA-256 " + sha256(path))


def classes(jar):
    return {name for name in jar.namelist() if name.endswith(".class")}


def check_bytecode(jar):
    versions = collections.Counter()
    entries = [entry for entry in jar.infolist() if entry.filename.endswith(".class")]
    if len(entries) != len({entry.filename for entry in entries}):
        raise ValueError("Duplicate class entries in " + jar.filename)
    for entry in entries:
        data = jar.read(entry)
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("Invalid class: " + entry.filename)
        major = struct.unpack_from(">H", data, 6)[0]
        versions[major] += 1
    if not versions or set(versions) != {52}:
        raise ValueError("Expected all Java 8 classfiles (major 52): " + repr(dict(versions)))
    return sum(versions.values())


def source_digest(root):
    paths = []
    for directory in ("main/src", "v1_8_R3/src", "dist/src", "scripts/aigot"):
        paths.extend(p for p in (root / directory).rglob("*")
                     if p.is_file() and "__pycache__" not in p.parts and "target" not in p.parts)
    paths.extend(root / name for name in (
        "build-aigot.sh", "pom-aigot.xml", "main/pom-aigot.xml",
        "v1_8_R3/pom-aigot.xml", "dist/pom-aigot.xml",
        "docs/THIRD-PARTY-NOTICES-AIGOT.txt", "docs/licenses/libby-MIT.txt",
    ))
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


def check_adapter_test_reports(root):
    expected = {path.stem for path in (root / "v1_8_R3/src/test/java").rglob("*Test.java")}
    found = set()
    count = 0
    versions = set()
    for path in (root / "v1_8_R3/target/aigot/surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        name = suite.attrib["name"].split(".")[-1]
        if name not in expected:
            continue
        properties = {node.attrib["name"]: node.attrib["value"]
                      for node in suite.findall("properties/property")}
        version = properties.get("java.version", "")
        if not version.startswith("1.8."):
            raise ValueError("Adapter regression suite did not run on Java 8: " + name + " (" + version + ")")
        classpath = properties.get("surefire.test.class.path", properties.get("java.class.path", "")).split(os.pathsep)
        main_jar = str(root / "main/target/aigot" / ("citizens-main-" + VERSION + ".jar"))
        if main_jar not in classpath:
            raise ValueError("Adapter tests did not use the packaged shaded main JAR: " + name)
        main_index = classpath.index(main_jar)
        if str(root / "main/target/aigot/classes") in classpath:
            raise ValueError("Unshaded main classes mask packaged behavior in suite: " + name)
        if any("/citizensapi/" in entry.replace("\\", "/") for entry in classpath[:main_index]):
            raise ValueError("Standalone CitizensAPI masks packaged main behavior in suite: " + name)
        if any(int(suite.attrib.get(key, "0")) for key in ("failures", "errors", "skipped")):
            raise ValueError("Adapter regression suite failed or skipped tests: " + name)
        tests = int(suite.attrib.get("tests", "0"))
        if tests == 0:
            raise ValueError("Empty adapter regression suite: " + name)
        count += tests
        versions.add(version)
        found.add(name)
    if not expected or expected != found:
        raise ValueError("Missing Java 8 adapter regression reports: " + repr(sorted(expected - found)))
    return {"tests": count, "suites": sorted(found), "java_versions": sorted(versions),
            "packaged_shaded_main_verified": True}


def verify(plugin, server, root, api, java_version=None, maven_version=None):
    with zipfile.ZipFile(plugin) as jar, zipfile.ZipFile(server) as private_jar:
        count = check_bytecode(jar)
        names = set(jar.namelist())
        required = {
            "plugin.yml", "net/citizensnpcs/Citizens.class",
            "net/citizensnpcs/api/CitizensAPI.class",
            "net/citizensnpcs/nms/v1_8_R3/util/NMSImpl.class",
            "LICENSE", "MODIFICATIONS-AIGOT.txt", "META-INF/THIRD-PARTY-NOTICES-AIGOT.txt",
            "META-INF/licenses/libby-MIT.txt", "META-INF/licenses/citizensapi/LICENSE",
        }
        if not required <= names:
            raise ValueError("Incomplete Citizens distribution: " + repr(sorted(required - names)))
        prefix = "net/citizensnpcs/nms/"
        adapters = {name[len(prefix):].split("/")[0] for name in names
                    if name.startswith(prefix) and name.endswith(".class")}
        if adapters != {"v1_8_R3"}:
            raise ValueError("Expected only the v1_8_R3 adapter: " + repr(sorted(adapters)))
        leaked = classes(jar) & classes(private_jar)
        if leaked:
            raise ValueError("Server input classes leaked into plugin: " + repr(sorted(leaked)[:20]))
        forbidden = ("net/minecraft/", "org/bukkit/", "net/aigot/", "META-INF/maven/net.aigot/")
        if any(name.startswith(forbidden) for name in names):
            raise ValueError("Provided server/API dependency leaked into plugin")
        if any(name.startswith(("clib/net/kyori/", "META-INF/maven/net.citizensnpcs/citizens-aigot-test-runtime/"))
               for name in names):
            raise ValueError("Test-only runtime libraries leaked into plugin")
        for path in (root / "v1_8_R3/src/test/java").rglob("*.java"):
            if str(path.relative_to(root / "v1_8_R3/src/test/java").with_suffix(".class")) in names:
                raise ValueError("Adapter regression test class leaked into plugin: " + path.name)
        plugin_yml = jar.read("plugin.yml").decode("utf-8")
        if "version: " + VERSION + " (build aigot-1)" not in plugin_yml:
            raise ValueError("plugin.yml does not contain the honest AIgot build label")
        for entry, source in (
            ("LICENSE", root / "main/src/main/resources/LICENSE"),
            ("MODIFICATIONS-AIGOT.txt", root / "main/src/main/resources/MODIFICATIONS-AIGOT.txt"),
            ("META-INF/THIRD-PARTY-NOTICES-AIGOT.txt", root / "docs/THIRD-PARTY-NOTICES-AIGOT.txt"),
            ("META-INF/licenses/libby-MIT.txt", root / "docs/licenses/libby-MIT.txt"),
        ):
            if jar.read(entry) != source.read_bytes():
                raise ValueError("License/attribution content changed during packaging: " + entry)
    with zipfile.ZipFile(api) as jar:
        api_count = check_bytecode(jar)
        api_license = jar.read("META-INF/licenses/citizensapi/LICENSE")
    with zipfile.ZipFile(plugin) as jar:
        if jar.read("META-INF/licenses/citizensapi/LICENSE") != api_license:
            raise ValueError("CitizensAPI license was not preserved in the distribution")
    git_revision = subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
    ).strip()
    dirty = bool(subprocess.check_output(
        ["git", "-C", str(root), "status", "--porcelain"], text=True
    ).strip())
    adapter_tests = check_adapter_test_reports(root)
    record = {
        "version": VERSION,
        "citizens_upstream_base": UPSTREAM_REVISION,
        "citizens_checkout_revision": git_revision,
        "citizens_checkout_has_changes": dirty,
        "citizens_build_sources_sha256": source_digest(root),
        "citizens_api_revision": API_REVISION,
        "citizens_api_coordinate": "net.citizensnpcs:citizensapi:2.0.35-aigot-api-e1959813-1",
        "citizens_api_sha256": sha256(api),
        "citizens_api_class_count": api_count,
        "protocollib_release": "5.0.0",
        "protocollib_sha256": "41d7d8e99e21eebd343c04c07e6b5afff79cad28240afe1dbad0cce52402d2a6",
        "spigot_api_version": "1.21-R0.1-20240807.214924-87",
        "private_aigot_input_sha256": sha256(server),
        "private_aigot_revision_user_reported": os.environ.get("AIGOT_REVISION"),
        "private_aigot_expected_sha256": os.environ.get("AIGOT_SHA256"),
        "plugin_sha256": sha256(plugin),
        "plugin_class_count": count,
        "classfile_major": 52,
        "nms_adapters": sorted(adapters),
        "private_server_classes_bundled": False,
        "test_runtime_libraries_bundled": False,
        "java8_adapter_regressions": adapter_tests,
        "minecraft_server_gameplay_tested_by_this_script": False,
    }
    for name, path in (("java_build_tool", java_version), ("maven_build_tool", maven_version)):
        if path:
            record[name] = pathlib.Path(path).read_text().strip()
    output = pathlib.Path(str(plugin) + ".build.json")
    output.write_text(json.dumps(record, indent=2) + "\n")
    pathlib.Path(str(plugin) + ".sha256").write_text(sha256(plugin) + "  " + pathlib.Path(plugin).name + "\n")
    print("Verified %d Java 8 classes; only v1_8_R3; no private server classes bundled" % count)
    print("Verified %d adapter regression tests passed on Java 8" % adapter_tests["tests"])


def main():
    args = sys.argv[1:]
    if args[0] == "--check-input":
        check_input(args[1])
    elif args[0] == "--check-sha256":
        actual = sha256(args[1])
        if actual != args[2]:
            raise ValueError("Checksum mismatch for " + args[1] + ": " + actual)
        print("Checksum verified: " + pathlib.Path(args[1]).name)
    else:
        verify(args[0], args[1], pathlib.Path(args[2]).resolve(), *args[3:])


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit("AIgot verification failed: " + str(error))
