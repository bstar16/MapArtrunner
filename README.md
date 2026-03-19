# MapArt - Fabric 1.21.4 Mod

A Minecraft Fabric mod for Minecraft 1.21.4

## Development Setup

### Prerequisites
- JDK 21 or higher
- Git

### Getting Started

1. **Clone and navigate to the project:**
   ```bash
   git clone <your-repo-url>
   cd MapArtrunner
   ```

2. **Generate the Minecraft development environment:**
   ```bash
   ./gradlew genSources
   ```
   (On Windows, use `gradlew.bat genSources`)

3. **Build the mod:**
   ```bash
   ./gradlew build
   ```

The compiled mod JAR will be located in `build/libs/`.

### IDE Setup

#### IntelliJ IDEA
1. Open the project in IntelliJ
2. Run `./gradlew idea` to generate IDE configurations
3. Reload the project

#### Eclipse
1. Run `./gradlew eclipse` to generate IDE configurations
2. Import the project as an existing project in Eclipse

#### VS Code
1. Install the "Extension Pack for Java" extension
2. Run `./gradlew build` to generate necessary files
3. Open the project in VS Code

### Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com/example/mapart/
│   │       └── MapArtMod.java          # Main mod entry point
│   └── resources/
│       ├── fabric.mod.json              # Mod metadata
│       ├── mapart.mixins.json           # Mixin configuration
│       └── assets/mapart/               # Game assets (textures, sounds, etc.)
build.gradle                             # Gradle build configuration
gradle.properties                        # Project properties and versions
settings.gradle                          # Gradle settings
```

## Building and Running

**Build the mod:**
```bash
./gradlew build
```

**Development build:**
```bash
./gradlew genSources
./gradlew build
```

## In-Game Commands

The mod registers these command roots:
- `/mapart` (primary)
- `/maprunner` (legacy alias)
- `/mapartrunner` (mod-name alias)

Available subcommands:
- `load <path>` (OP level 2 required)
- `info`
- `unload` (OP level 2 required)
- `panic` (client-side emergency stop + unload)
- `clienttimerspeed <multiplier>` (sets how many assisted automation passes run each client tick; useful for testing)

Example:
```mcfunction
/mapart load /absolute/path/to/build.schem or .nbt
/mapart info
/mapart clienttimerspeed 4
/mapart unload
/mapart panic
```

## Panic Button

The mod now exposes a client keybind named **MapArt → Panic unload** in the Minecraft Controls menu.

- Bind it to any keyboard or mouse button you want.
- Pressing it immediately cancels active automation, closes any open supply container UI, and unloads the current plan.
- The same action is also available as `/mapart panic`.

If `/mapart` is unknown in game:
- make sure the built JAR from `build/libs/` is in your server/client `mods/` folder
- confirm Fabric API is also installed on the same instance
- run `/help mapart` to verify command registration


## Baritone Integration

MapArtRunner integrates with Baritone through `baritone-api-fabric` at runtime.

- Install a Baritone jar that exposes the `baritone-api-fabric` API for your exact Minecraft version (1.21.4 in this project).
- If Baritone is missing or the API is mismatched, `/mapart start` now reports a friendly in-game error and assisted movement is disabled until a compatible Baritone jar is installed.

## Troubleshooting

If the standard commands fail even when typed correctly, check the items below.

1. **Verify Java version (must be 21+):**
   ```bash
   java -version
   ```
   If this shows a lower version, install JDK 21 and set `JAVA_HOME` to that installation.

2. **Linux/macOS permission error (`./gradlew: Permission denied`):**
   ```bash
   chmod +x gradlew
   ./gradlew build
   ```

3. **Use the correct Gradle wrapper command for your platform:**
   - Linux/macOS: `./gradlew build`
   - Windows PowerShell/CMD: `gradlew.bat build`

4. **Refresh Gradle cache if dependencies are corrupted:**
   ```bash
   ./gradlew --stop
   ./gradlew --refresh-dependencies build
   ```

## Mod Details

- **Minecraft Version:** 1.21.4
- **Fabric Loader:** 0.16.5
- **Fabric API:** 0.104.0+1.21.1
- **Java Version:** 21

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
