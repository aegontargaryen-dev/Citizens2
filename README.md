AIgot compatibility fork
========================

This branch is a modified, AIgot-only Citizens 2.0.35 port based on upstream build 3478.
It requires the matching AIgot integration hooks; an ordinary Spigot/AIgot 1.8.8
server is not interchangeable. Use [the isolated build guide](docs/BUILD-AIGOT.md)
and `./build-aigot.sh`, rather than the upstream multi-version build below.
JDK 21 compiles Java 8 bytecode; a Java 8 runtime executes the native adapter tests.
No private server code or server binary is included. Runtime gameplay validation
is still required before deployment.

Citizens2 README
================

Citizens is an NPC plugin for the Bukkit API. It was first released on March 5, 2011, and has since seen numerous updates. Citizens provides an API which developers can use to create their own NPC characters. More information on the API can be found on the API page of the Citizens Wiki (https://wiki.citizensnpcs.co/API).

Compatible with:
* Minecraft (for specific compatible version information, see https://wiki.citizensnpcs.co/Versions for info)
* CitizensAPI (for compiling purposes only)

Extra information
=================

Javadoc: http://jd.citizensnpcs.co

Spigot page: https://www.spigotmc.org/resources/citizens.13811

Developmental builds: https://ci.citizensnpcs.co/job/Citizens2/

For questions/help join our discord at: https://discord.gg/Q6pZGSR
