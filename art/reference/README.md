# Reference art

Timothy's originals, kept here because `.gradle/` is a build directory and gets wiped.

- `dirt_clod.png` — hand-drawn clod, the source for the soil grain sprite (Nov 2024)
- `bedrock_igneous_block.png`, `bedrock_metamorphic_block.png`, `bedrock_sedimentary_block.png` —
  one greyscale texture in three tints (Nov–Dec 2024). These calibrated the stone saturation: mean
  chroma 0 / 14 / 29, which is what set `ROCK_DESATURATION`.

Sprites in `src/main/resources` are derived from these by `tools/extract_textures.py`, greyscaled
and stretched into the tint band. These are the originals; edit these, not the derived ones.
