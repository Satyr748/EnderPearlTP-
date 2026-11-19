# EnderPearlTP – Fabric 1.21.11

Server-side Fabric mod project for Java 21.

## What this starter project does
- `/enderpearl` lists online players and shows selection commands in chat.
- `/eptoggle <player>` toggles a player in your selection.
- `/epconfirm` gives you a normal Fishing Rod.
- `/enderpearltp` is an alias-style shortcut that directly gives you a rod.
- Right-clicking the issued rod consumes it even in Creative and teleports selected online players to your current position, with a ring of portal particles.

## Important note about the menu
This first packaged version uses clickable-style chat instructions rather than a chest GUI. A true server-side chest GUI can be added next; vanilla inventory menus do not require clients to install the mod.

## Build locally
1. Install a Java 21 JDK.
2. Open this folder in IntelliJ IDEA, or run `gradle build`.
3. The mod JAR will be in `build/libs/`.

## Build on GitHub
1. Create a GitHub repository and upload all files from this ZIP.
2. Push/commit to the default branch.
3. Open the repository's **Actions** tab.
4. Wait for **Build Fabric Mod** to finish.
5. Open the completed workflow run and download the `EnderPearlTP` artifact. It contains the JAR.

## Install on the server
Put the built mod JAR and Fabric API JAR in the server's `mods` folder, then restart the server. The server must run Fabric 1.21.11 and Java 21.

## Permissions
Commands require permission level 2 (operator by default).

## Compatibility note
This source is prepared for Minecraft 1.21.11, Yarn mappings build 6, Fabric Loader 0.18.1, and Fabric API 0.141.1+1.21.11. It has not been compiled in this environment; if Gradle reports an API/mapping mismatch, share the build log and I can adjust it.
