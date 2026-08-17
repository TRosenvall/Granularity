# Rendering: the two paths, and what falls between them

Every rendering bug in this project so far has come from the same place: **a placed block and its item
form are drawn by two different pipelines**, and code that serves one silently does nothing for the
other. Five bugs in a row during the smooth-stone work, each one compiling cleanly, passing the whole
suite, logging nothing, and looking wrong on screen.

This is the file to read before touching anything that draws.

## The two paths

**A placed block** is drawn by chunk meshing. It has a block entity, so it can be handed
`ModelData`, and the mesher calls `getQuads(state, side, rand, data, renderType)` directly. Nothing
else is consulted.

**An item** has no block entity and therefore no model data. It is drawn roughly like this:

1. `ItemModelShaper` looks up the baked model for the item.
2. `model.getOverrides().resolve(model, stack, …)` — the only place a per-stack decision can be made,
   because the stack is the only thing that knows what this particular item is.
3. `model.applyTransform(context, poseStack, leftHand)` — **returns a model**, and whatever it
   returns is what continues.
4. `model.getRenderPasses(stack, fabulous)` — **returns models**, and those are what get drawn.
5. `getQuads(state, side, rand)` — the *three-argument* overload, on whatever survived steps 3 and 4.

## The rule that follows

> **A `BakedModelWrapper` meant to survive item rendering must override every method that returns a
> model — not merely the ones that return quads.**

`BakedModelWrapper` delegates `applyTransform` and `getRenderPasses` to the model it wraps. For a
wrapper whose entire purpose is to draw something *different*, delegating either one hands back the
very model it exists to replace, and the wrapper is discarded before it is asked for a single quad.

Both `OverlayBakedModel` and `FinishBakedModel` override both, applying the transform for its side
effect on the pose stack and returning `this`.

The symptom is unmistakable once you know it: **the world looks right and the hand looks wrong.**
Blocks never call `applyTransform` or `getRenderPasses`, so the block path keeps working perfectly
while the item path draws the wrapped model. If you see that symptom, grep for `applyTransform`
first — it cost three wrong fixes to relearn this the second time.

Also remember the three-argument `getQuads(state, side, rand)`. Item rendering calls that one, not
the `ModelData` overload, so a wrapper that only overrides the five-argument form works for blocks
and does nothing for items.

## Per-stack caches

`OverlayItemModel` caches one resolved model per distinct appearance, because `resolve` runs for
every item in every slot every frame. **Any such cache key must distinguish everything that changes
the appearance.**

That key was overlays-plus-dye, which was correct while cobbled and smooth were separate items with
separate caches — and became a collision the moment they shared one item. A smooth stack and a
cobbled stack with the same dye resolved to the same cached model, so a stack drew as whichever was
resolved first, and washing the dye off changed the key and appeared to "fix" it.

**Collapsing two items into one invalidates every per-stack cache whose key no longer tells them
apart.** Audit them deliberately; this will recur when gravel becomes derived porosity.

The tell for a cache collision specifically: **the name is right and the texture is wrong.** Names
are read from components every time; models come from the cache.

## Blockstate variant strings name every property

A variant string is not just the property you care about. A slab's reads
`type=bottom,waterlogged=false`, so matching it against `type=bottom` finds nothing — quietly,
because an unmatched variant simply goes unwrapped and draws the default. Parse the property out;
never compare whole variant strings.

## Names are per form, not per block

A finish selects the *translation key* rather than prefixing the name, because English does not
prefix it — smelted slate is "Smooth Slate", not "Smooth Slate Cobblestone". So **every finishable
form needs its own pair of keys** (`…smooth` and `…smooth.named`). Miss one and the player is shown a
raw translation key. `FinishNamingTest` guards this.

## Make silent failures loud

None of the above throws. Nothing logs. The build is green and the tests pass. The only signal is a
person looking at the screen — so put a counter at startup where a human will see it:

```
Overlay-capable models: 518 block variants and 12 item models across 14 composite blocks
Finish-capable models: 411 variants can draw smooth.
```

That number caught the variant-string bug immediately: it read `1` when it should have read `7`, and
a wrong count is visible in a way that a wrong texture is not. When adding a wrapper, add a count of
what it wrapped, and check the number rather than trusting that the code compiled.

## Debugging these

Instrument before changing code. Three fixes were made on reasoning about how the API *ought* to
behave, and each was a real defect that was not the bug in hand.

Two practical notes:

- **Log at the entry points**, not around them. "Which method was called at all" narrowed the last bug
  in one round after three rounds of guessing.
- **Watch the dedupe key of your own diagnostic.** One log deduplicated by face direction alone, so
  once chunk rendering had logged all six faces, every item-path call was suppressed — making "the
  item path never calls this" indistinguishable from "I am not printing it", and costing a round trip.
  Include whatever separates the paths; `state == null` means item.
