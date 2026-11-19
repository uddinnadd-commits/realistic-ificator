# Realistic-Ificator

A Fabric mod for **Minecraft 26.3** that adds the **Realistic-Ificator**, a gadget that turns ordinary blocks into *realistic* physics blocks.

## How to use

1. Craft the Realistic-Ificator (or grab it from the *Tools & Utilities* creative tab):

   ```
   I R I      I = iron ingot
   G S G      R = block of redstone
   I I I      G = glass, S = smithing table
   ```

2. Right-click with it to open a smithing-table style screen. Put a stack of blocks in the left slot and take the realistic version out of the right slot.
3. Place a realistic block like any other block. It appears in its realistic form.

## What realistic blocks do

| Behaviour | Details |
| --- | --- |
| **Solid collisions** | Players and mobs collide with realistic blocks and can stand on them. Every cell of a multi-block shape collides on its own, so L-shapes and steps work. |
| **Grows when you build on it** | Place another realistic block of the **same kind** on any face to grow the body (up to 64 cells). |
| **Tumbles** | Hit it to knock it over, or push it off a ledge: if its centre of mass hangs over an edge it tips, tumbles through the air and settles on its nearest flat side. |
| **Floats by weight** | Each block has a real-world density. Wood, wool, ice and leaves float in water at a depth that matches their density. Stone and metals sink. Anything lighter than about 3.1 g/cm³ floats on lava. |
| **Pushable** | Walk into it to shove it. Heavy blocks such as gold, iron or netherite barely budge. |
| **TNT disassembles it** | Explosions break the structure into single blocks that fly apart and tumble. Fragile blocks such as glass and leaves shatter. |
| **Break it** | Sneak and punch to break it. It drops its realistic blocks (in survival). |

## Building

You need JDK 25.

```
./gradlew build
```

The mod jar is written to `build/libs/realistic-ificator-<version>.jar`. Put it in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) and Fabric Loader 0.20.1 or newer.

GitHub Actions (`.github/workflows/build.yml`) builds every push and pull request and uploads the jar as an artifact.

`realistic-ificator.zip` in the repository root holds the full project source plus a prebuilt `realistic-ificator-1.0.0.jar`.
