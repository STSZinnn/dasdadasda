# Sovereign SMP v1.1 — Gravity Mace

Paper 1.21.11 / Java 21. Sovereign Mace is the first of seven artifacts.

## Install
Upload this project's *contents* to a GitHub repo. The provided `.github/workflows/build.yml` runs `gradle clean build`. Download the JAR from the action Artifact and install it in `plugins/`.

## Mace
* Right-click — Event Horizon: a longer-range aimed gravity field with strong repeated pull, particles, and layered sound effects.
* Sneak + right-click — Gravitational Collapse: original shockwave.
* F (swap hands) — Singularity: pulls targets, orbits **visual block displays** copied from surrounding terrain, then fires the visuals outward before removing them. **No real blocks are removed/changed.**
* Passive — Every third melee hit applies gravitational pressure.

## Artifact tooltip
Gradient name, ability descriptions, keybinds and cooldowns appear **without** a resource pack.
To add the special purple/gold tooltip background and frame:
1. Install `SovereignSMP_Tooltip_Pack_1.21.11.zip` on your Minecraft client or distribute through your server's `resource-pack` URL.
2. As OP run `/sovereign tooltip on`. Existing online player maces restyle automatically.
3. `/sovereign restyle` updates the held authentic mace or others in your inventory.
4. `/sovereign tooltip off` removes the custom background; gradients remain.
No weapon item textures or models are changed by this pack.

## Existing save compatibility
`artifacts.yml` and artifact token namespace remain unchanged. Existing v1.0 Sovereign Maces remain recognized. Only one owner can wield the mace. You can customize ability strengths in `plugins/SovereignSMP/config.yml`.

## Limits
The gradient background requires the optional pack, but no pack is needed for gradient text. Only the first artifact is implemented; the other 6 are reserved. The actual blocks around Singularity remain intact (only temporary display entities fly).
