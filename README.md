
# MapArt - Fabric 1.21.11 Mod

**this entire project is being made with codex/chagpt** - if this is a problem code ur own you dont have to use this or even better if you find a better way to code something that you think is better than what is being use then make a PR

## Description/Motivation

A Minecraft Fabric mod for Minecraft 1.21.11 designed to automate the process of making mapart and be portable.
-
I've been wanting to make this for a while when i had the idea long ago as im sure alot of people have and some of those ideas have come to life but they don't align with what i want and need. My idea of this mod was to make something to just replace the repetitive task of placing blocks over and over. Personally i never had an issue with getting the materials needed for a mapart even large ones but i would eventually get bored of placing the blocks which limited how much mapart i would dedicate my time to. So this project aims to make that fully automated while also having the ability to get deployed at any location which is a big thing for me. If i make it implemented correctly you can run multiple instances to speed up production time. Do i think this project negates the need to handplace blocks? **no** i still have alot of respect to people who handplace mapart and honestly admire the dedication so props to you guys which is most of the community :) 

**I am very open to recommendations and suggestions aswell as PR's so feel free to do as you wish just please be detailed unless its straigtforward**

## Goals
- Automate the entire process of building mapart this includes refilling the inventory
- Refill/Storage management
- Hands off once the build process is intiated
- Support for flat and staircasing (flat only for now while placements/movement is made fully stable)
- Potential support for shulkers in inventory for refill if more viable for less trips
- Elytra support to increase speed if placement speed permits it over grounded movement

## Development Setup

### Prerequisites
- JDK 21 or higher 
- Git

### Getting Started

1. **Clone and navigate to the project:**
   ```bash
   git clone <your-repo-url>
   cd <project-folder>
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
1. Import project
2. Wait for gradle to import
3. Press the build button in gradle
4. Compiled jar in `build/libs/` **don't pick the sources .jar**

## In-Game Commands
VVVVVVVVVV
- `/mapart` 

Available subcommands ( theres more but ill explain these because some are straightforward ) :
- `load <path>` e.g `/mapart load bedrock.nbt` ( place the schematic in the games folder for easier typing)
- `info` - will print the loaded schematics info useful for materials - will show total + X stacks + X blocks for each item
- `unload` - basically reset
- `panic` (client-side emergency stop + unload - Can be binded in keybinds )
- `clienttimerspeed <multiplier>` (sets how many assisted automation passes run each client tick; useful for testing - wouldn't use online unless server allows timer)
- `supply` `add <name>` - will prompt you to right click a container to set a supply point - `clear` clears the list but kinda broken haven't looked further into the issue but does work? `list` - shows supplys with coords and a number/id `remove <id>` add the id to remove it from the list
- `setorigin` you cannot start without an origin point so stand where you want it and issue the command or alternatively put the coords in after e.g `/mapart setorigin x y z`
- `settings` - will list all the settings and their values `set <setting> <value>` some are boolean, some are values so check beforehand - note `overlaycurrentregiononly false` will render the entire overlay rather then the current chunk/region
- **`debug` there is debug subcommands but you can explore them as you wish these are mainly for my testing purposes**


If `/mapart` is unknown in game:
- make sure the built JAR from `build/libs/` is in your server/client `mods/` folder
- confirm Fabric API is also installed on the same instance
- run `/help mapart` to verify command registration


## Baritone Integration

**this is still true but going forward the actual placement & movement within the schematic boundaries will be handle by the client not baritone with the exception of moving to start points and refill**

MapArtRunner integrates with [Baritone](https://github.com/cabaletta/baritone) through `baritone-api-fabric` at runtime.

- Install a Baritone jar that exposes the `baritone-api-fabric` API for your exact Minecraft version (1.21.11 in this project).
- If Baritone is missing or the API is mismatched, `/mapart start` now reports a friendly in-game error and assisted movement is disabled until a compatible Baritone jar is installed.
- Note 1.21.11 baritone api isn't in the releases however it was pushed into the main branch 3 weeks ago, linked [here](https://github.com/cabaletta/baritone/actions/runs/23763140478) will remove once available and these build files may also become dead eventually 

## Mod Details

- **Minecraft Version:** 1.21.11
- **Fabric Loader:** 0.16.5
- **Fabric API:** 0.141.3+1.21.11
- **Java Version:** 21

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
