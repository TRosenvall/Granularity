# The Material Model

**Amends `toy_geology_model/GEOLOGY_MOD_DESIGN.md` §3, §6 and §7. Author of the design: Timothy
Rosenvall, 2026-08-11. Written down here because the mod repository — not the prototype — is where
design after Deliverable 0 belongs.**

The design document specifies that a block is nine slots and that there are sixteen colours. It does
not say what can occupy a slot beyond a short list, nor what a block *is* once you know its
composition. This document does.

---

## 1. Two axes, not one

Colour is not the only axis. **Bedrock type is a second, orthogonal one**, and colour is *mediated
over* it.

- The colour field (§4) assigns a region one of sixteen colours.
- A bedrock-type field assigns a region a rock family.
- The pair determines what materials exist there — and **not every pair is valid**. A colour that
  makes no sense in a given bedrock type simply does not occur in the world.

So the material space is a *constrained* product, not a full 16 × N cross product. This matters for
registration: the naive reading of §3 is 64 materials, but with a second axis the count is however
many (type, colour) pairs survive the constraint, times the classes.

### The table (Timothy, 2026-08-11)

Three families — **igneous, metamorphic, sedimentary** — and the sixteen colours are *partitioned*
between them, one family per colour, no duplicates. Finding red rock identifies the family with no
ambiguity.

| family | areal share | colours |
|---|---|---|
| Sedimentary | 60% | white, orange, yellow, pink, brown, **red** |
| Igneous | 25% | light blue, lime, gray, cyan, **black** |
| Metamorphic | 15% | magenta, **light gray**, purple, blue, green |

Sedimentary takes the warm end — oxidised iron in sandstones and mudstones, carbonate for the
white. Igneous and metamorphic keep the greyscale anchors (basalt and gabbro; marble and quartzite)
plus a few hues a real mineral in that family can justify. Some are exaggerated on purpose: sixteen
discrete colours have to stay tellable apart.

Sedimentary rock covers ~73% of Earth's land surface while being under a tenth of the crust by
volume — a thin veneer over basement. The weights lean on the areal figures, pulled down from 73%
so the world does not read as overwhelmingly warm. Measured in world: 60.0 / 25.4 / 14.6.

**Known simplification.** Family is a property of the region — a 2D field, like colour, per §4's
bedrock-as-map. Real geology stacks these vertically. A depth axis would be more honest and is not
in the design; noted rather than assumed away.

## 2. What can occupy a slot

Nine classes. Seven carry a colour; two do not.

| class | coloured | notes |
|---|---|---|
| `AIR` | no | Porosity. A real occupant, not an absence. |
| `ROCK` | yes | |
| `ORE` | yes | |
| `PRECIOUS_ORE` | yes | |
| `GEM` | yes | |
| `SAND` | **yes** | Sand has types, because sandstone is made of them |
| `SILT` | **yes** | |
| `CLAY` | **yes** | |
| `WATER` | no | Invisible; never an item |

The change from the previous implementation is that **sand, silt and clay are coloured**. They were
colourless, on the reading that §3's lattice covered only the four mineral classes. That reading was
wrong: if a sandstone block is nine sand piles, then sand needs varieties in the same way rock does,
and topsoil needs a palette to mix from.

## 3. A block's identity is a function of its composition

This is §7's governing rule — *majority class determines identity, composition determines the rest,
thresholds add behaviours* — with the thresholds filled in.

| composition | is |
|---|---|
| nine sand | sandstone |
| sand + silt + clay, mixed | **topsoil** — a supertype, not a block someone invented |
| rock/ore/precious/gem, with **> 5 air** | gravel |
| rock/ore/precious/gem, low air | stone |
| any, with water slots | wet — mud, marsh, shoreline, per §7 |

Topsoil being a *supertype* is the important word. Anything that reads as a dirt texture is
primarily sand, silt and clay in some proportion — which is the USDA soil texture triangle §6
already names, now with the constituents carrying colour so the soil takes its colour from what it
is made of.

Gravel falls out the same way: it is not a new material but ordinary stone constituents that have
lost more than five slots to air.

## 4. Air and water are slots that yield no item

Both occupy slots. Neither becomes an item in the inventory.

**Air (porosity).** A block with air slots drops **fewer than nine objects** — the missing ones are
the air. Consequences, all of which the design already wanted and none of which need new machinery:

- More air ⇒ **breaks faster** (§6's destroy-speed modifier).
- More air ⇒ more room for water to infiltrate (§6's perched tables, findings §6.1's "the free
  slots *are* the porosity").
- More than five air ⇒ the block is gravel (§3 above).

**Water.** A water slot drops a water *drop*, but the drop never manifests as an item. It appears in
the world as a single layer of water where the block was. Nine water drops is a source block (§7),
so one drop is one ninth of one — the thinnest layer the fluid layer can express.

This is the first place drop count stops being exactly nine, and it is deliberate. §2's "drops 9
objects" is about the nine *slots*; how many of them you can hold is a separate question.

> **Side effect worth noting.** Porous blocks yielding fewer items is a small, natural relief on the
> 9× inventory pressure §15 flags — not a solution, but it means the pressure is not uniform.

## 5. What this changes about what is built

Sorted by which phase actually owns it, so none of this gets built ahead of its turn.

| area | phase | status |
|---|---|---|
| Sand/silt/clay carry colour | 3 | **done** — `Material`, `MaterialClass` |
| One item per class, colour by component + tint | 3 | **done** — `MaterialObjectItem`, `MaterialItemColour` |
| Air as a slot occupant | — | representation already present (`MaterialClass.AIR`) |
| Air/water yield no item | — | already true in `CompositionDrops` |
| Bedrock-type axis, partitioning the lattice | 3 | **done** — `BedrockType`, `ColourField` |
| Water manifesting as a layer on break | 6 | not built |
| Porosity in derived stone | 7 | not built |
| Composition → block identity (sandstone / topsoil / gravel) | 7 | not built |
| What the materials actually are | last | deliberately deferred |

## 6. Open questions

1. **Do sand, silt and clay share the 16-colour lattice** or have palettes of their own? Sharing is
   cheaper and makes soil colour arithmetic uniform with rock; separate palettes would let soil read
   as soil rather than as tinted rock.
3. **Gravel's threshold interacts with sandstone's.** More than five air makes gravel — but what is
   five air plus four sand? Sand-dominant with high porosity is arguably gravel too, or arguably
   loose sand. The rule as stated only covers the mineral classes.
4. **What names the materials.** Deliberately deferred; see §3 of the design's own open questions.
   Timothy's instruction is that drops and their real-world representation come *last*.
