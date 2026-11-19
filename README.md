# Realistic-Ificator

A Fabric mod for **Minecraft 26.3** that adds the **Realistic-Ificator**, a gadget that turns ordinary blocks into *realistic* physics blocks and spawn eggs into ragdoll corpses, plus a Gmod-style Physics Gun to throw them around.

## How to use

1. Craft the Realistic-Ificator (or grab it from the *Tools & Utilities* creative tab):

   ```
   I R I      I = iron ingot
   G S G      R = block of redstone
   I I I      G = glass, S = smithing table
   ```

2. Right-click with it to open a smithing-table style screen. Put a stack of blocks (or spawn eggs) in the left slot and take the realistic version out of the right slot.
3. Place a realistic block like any other block. It appears in its realistic form.

## What realistic blocks do

| Behaviour | Details |
| --- | --- |
| **Real rigid-body physics** | Each body has mass, a centre of mass and an inertia tensor built from its blocks. Gravity, contact forces, friction and bounce move and rotate it continuously, with no canned animations. Bodies land on corners, roll, tip over ledges, lean against walls and pile up on each other. |
| **Solid collisions** | Players and mobs collide with realistic blocks and can stand on them, even when tilted. A moving body carries whoever stands on it, and their weight can tip it like a see-saw. |
| **Grows when you build on it** | Place another realistic block of the **same kind** on any face, at any angle, to grow the body (up to 1024 blocks). |
| **Hitting** | Punching applies an impulse where you hit, so striking the top of a tall body tips it and striking an edge spins it. Sprinting and stronger weapons hit harder. Fast heavy bodies hurt whatever they slam into. |
| **Materials** | Friction comes from the blocks touching: things slide a long way on ice, slime blocks bounce (as a body, or as the floor something lands on), honey is sticky, and wool or sand barely bounce. |
| **Floats by weight** | Each block has a real-world density, and buoyancy acts on every block separately. Wood floats about 60% under, ice about 92% under, and stone and metals sink. Anything lighter than about 3.1 g/cm³ floats on lava. |
| **Pushable** | Walk into it to shove it at the point of contact. Light blocks slide, stone barely moves, and iron or gold won't budge unless it's on ice. |
| **TNT disassembles it** | Explosions break the structure into single blocks that fly apart and tumble. Fragile blocks such as glass and leaves shatter. |
| **Break it** | Sneak and punch to break it. It drops its realistic blocks (in survival). |

Slabs, stairs and other partial blocks keep their real shape, both for physics and for walking on them.

## Ragdoll corpses

Put a **spawn egg** into the Realistic-Ificator to get a *Realistic* spawn egg. Using it on the ground spawns a ragdoll corpse of that mob instead of a living mob. Corpses keep the mob's look and variant.

- The corpse is a set of rigid bones (head, torso, arms and legs, or four legs for animals) joined by ball joints with angle limits. It slumps, folds over ledges and tumbles down stairs.
- Corpses float (flesh is slightly lighter than water), get blown around by TNT, can be stood on, and are shoved by falling realistic blocks.
- Punch a corpse to knock it where you hit it. Sneak and punch to remove it.

## Structure Wand

```
  A E      A = amethyst shard, E = eye of ender
  B A      B = blaze rod
B
```

Turns a whole build realistic at once:

1. **Right-click** a block to set the first corner, then **right-click** the opposite corner. The selected box is outlined with particles while you hold the wand, and the corners are shown in its tooltip.
2. **Sneak + right-click** to make everything inside realistic. Each connected group of blocks becomes its own physics body, so a house and a separate tree fall separately.

Blocks that can't be realistic (chests, doors, fluids, bedrock) stay in place. Up to 4096 blocks per conversion and 1024 per body.

## Physics gun

```
  G D      D = diamond, G = glass, E = ender pearl
I R G      R = block of redstone, I = iron ingot
E I
```

Works like Garry's Mod:

- **Hold right-click** on a realistic block or corpse to pick it up. It follows your crosshair and you can walk and sprint while carrying it. Grabbing a corpse by a limb drags it by that limb.
- **Let go** and it keeps the speed you swung it with, so you can throw it.
- **Sneak and release** to freeze it in mid-air. Grab it again or hit it to unfreeze it.
- **Sneak and walk forward or back** while holding to push it away or pull it closer.

## Building

You need JDK 25.

```
./gradlew build
```

The mod jar is written to `build/libs/realistic-ificator-<version>.jar`. Put it in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) and Fabric Loader 0.20.1 or newer.

GitHub Actions (`.github/workflows/build.yml`) builds every push and pull request and uploads the jar as an artifact.

`realistic-ificator.zip` in the repository root holds the full project source plus a prebuilt `realistic-ificator-1.0.0.jar`.
