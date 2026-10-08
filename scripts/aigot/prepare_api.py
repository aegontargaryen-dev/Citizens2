#!/usr/bin/env python3
"""Write a separate, explicitly labelled build POM for the pinned upstream API."""
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

API_REVISION = "e19598137d192935117d013f1674d10ca3a971bf"
API_VERSION = "2.0.35-aigot-api-e1959813-1"
SPIGOT_VERSION = "1.21-R0.1-20240807.214924-87"
NS = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", NS)


def q(name):
    return "{" + NS + "}" + name


def add(parent, name, value=None):
    element = ET.SubElement(parent, q(name))
    element.text = value
    return element


def main():
    source = pathlib.Path(sys.argv[1]).resolve()
    revision = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != API_REVISION:
        raise SystemExit("CitizensAPI source is not the pinned revision: " + revision)
    if subprocess.check_output(
        ["git", "-C", str(source), "status", "--porcelain", "--untracked-files=no"],
        text=True,
    ).strip():
        raise SystemExit("CitizensAPI has modified tracked files; use a clean clone")
    tree = ET.parse(source / "pom.xml")
    root = tree.getroot()
    root.find(q("version")).text = API_VERSION
    properties = root.find(q("properties"))
    properties.find(q("bukkit.version")).text = SPIGOT_VERSION
    add(properties, "project.build.outputTimestamp", "2024-07-19T03:19:32Z")
    add(properties, "maven.deploy.skip", "true")
    repositories = root.find(q("repositories"))
    central = ET.Element(q("repository"))
    add(central, "id", "central")
    add(central, "url", "https://repo.maven.apache.org/maven2")
    add(add(central, "snapshots"), "enabled", "false")
    repositories.insert(0, central)
    repository = add(repositories, "repository")
    add(repository, "id", "official-spigot-snapshots")
    add(repository, "url", "https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    dependency = add(root.find(q("dependencies")), "dependency")
    for key, value in (("groupId", "junit"), ("artifactId", "junit"),
                       ("version", "4.13.2"), ("scope", "test")):
        add(dependency, key, value)
    # Translator imports this directly. Do not rely on Vault's obsolete Bukkit
    # dependency to accidentally supply it on the compiler classpath.
    dependency = add(root.find(q("dependencies")), "dependency")
    for key, value in (("groupId", "com.googlecode.json-simple"),
                       ("artifactId", "json-simple"), ("version", "1.1.1"),
                       ("scope", "provided")):
        add(dependency, key, value)
    exclusion = add(add(dependency, "exclusions"), "exclusion")
    add(exclusion, "groupId", "junit")
    add(exclusion, "artifactId", "junit")
    for dependency in root.find(q("dependencies")):
        if dependency.find(q("artifactId")).text == "VaultAPI":
            exclusion = add(add(dependency, "exclusions"), "exclusion")
            add(exclusion, "groupId", "org.bukkit")
            add(exclusion, "artifactId", "bukkit")
    managed = add(add(root, "dependencyManagement"), "dependencies")
    for group, artifact, version in (
        ("com.sk89q.worldedit", "worldedit-bukkit", "7.2.0-20201102.221009-187"),
        ("com.sk89q.worldedit", "worldedit-core", "7.2.0-20201102.221009-185"),
        ("com.sk89q.worldedit.worldedit-libs", "core", "7.2.0-20201102.221009-188"),
    ):
        dependency = add(managed, "dependency")
        for key, value in (("groupId", group), ("artifactId", artifact), ("version", version)):
            add(dependency, key, value)
    build = root.find(q("build"))
    build.find(q("defaultGoal")).text = "package"
    add(build, "directory", "${project.basedir}/target/aigot")
    resources = add(build, "resources")
    resource = add(resources, "resource")
    add(resource, "directory", "${project.basedir}")
    add(resource, "targetPath", "META-INF/licenses/citizensapi")
    add(resource, "filtering", "false")
    add(add(resource, "includes"), "include", "LICENSE")
    plugins = build.find(q("plugins"))
    for plugin in plugins:
        if plugin.find(q("artifactId")).text == "maven-compiler-plugin":
            plugin.find(q("version")).text = "3.13.0"
            configuration = plugin.find(q("configuration"))
            for element in list(configuration):
                configuration.remove(element)
            add(configuration, "release", "8")
            add(configuration, "proc", "none")
    for artifact, version in [
        ("maven-clean-plugin", "3.2.0"),
        ("maven-resources-plugin", "3.3.1"),
        ("maven-surefire-plugin", "3.2.5"),
        ("maven-install-plugin", "3.1.2"),
    ]:
        plugin = add(plugins, "plugin")
        add(plugin, "groupId", "org.apache.maven.plugins")
        add(plugin, "artifactId", artifact)
        add(plugin, "version", version)
    ET.indent(tree, space="    ")
    tree.write(source / "pom-aigot.xml", encoding="UTF-8", xml_declaration=True)


if __name__ == "__main__":
    main()
