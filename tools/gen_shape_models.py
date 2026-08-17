#!/usr/bin/env python3
"""Generate the layered models for composition-carrying shapes.

Every crafted composite is *one* model holding ten coincident copies of its geometry: a matrix layer
at tintindex 0 and nine stone layers at tintindex 1-9. Because the geometry lives inside a single
model, the blockstate can rotate it (stairs) and multipart can select it (walls) exactly as it would
for any vanilla model. Composing layers after baking -- which is what this replaced -- could do
neither, and that, not the geometry, is what kept stairs and walls out of reach.

Nothing here knows about moss, or about any overlay. An overlay covers exactly the boxes the stone
occupies, so it is drawn by re-issuing the block's own quads with a different sprite at render time
-- see OverlayBakedModel. That is why this file emits no overlay variants and never will: N overlays
would otherwise multiply every model and every blockstate variant by 2**N, and no generated file can
anticipate an overlay another mod has not written yet.

The shapes below are vanilla's own boxes, so a Granularity stair sits flush against a vanilla one.
"""
import json
import os

ASSETS = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "granularity")
FACES = ("down", "up", "north", "south", "west", "east")

# Must match CompositeBlockColour.UPPER_BASE.
UPPER_BASE = 10

# Must match CompositeBlockColour.WOOD_TINT and METAL_TINT. Above the double slab's 10-19 so the
# ranges cannot meet. These are the two materials on a block that are not its stone.
WOOD_TINT = 20
METAL_TINT = 21

# Must match CompositeBlockColour.PLAIN_TINT: "a real face, tinted by nothing".
PLAIN_TINT = 22

FULL = ((0, 0, 0), (16, 16, 16))
BOTTOM = ((0, 0, 0), (16, 8, 16))
TOP = ((0, 8, 0), (16, 16, 16))

# Vanilla's boxes, copied so our shapes line up with theirs.
BOXES = {
    "stairs":            [((0, 0, 0), (16, 8, 16)), ((8, 8, 0), (16, 16, 16))],
    "stairs_inner":      [((0, 0, 0), (16, 8, 16)), ((8, 8, 0), (16, 16, 16)), ((0, 8, 8), (8, 16, 16))],
    "stairs_outer":      [((0, 0, 0), (16, 8, 16)), ((8, 8, 8), (16, 16, 16))],
    "wall_post":         [((4, 0, 4), (12, 16, 12))],
    "wall_side":         [((5, 0, 0), (11, 14, 8))],
    "wall_side_tall":    [((5, 0, 0), (11, 16, 8))],
    "wall_inventory":    [((4, 0, 0), (12, 16, 16))],
}

def cullface(face, box):
    """A face is only cullable where it lies on the block boundary; interior faces must stay."""
    (x0, y0, z0), (x1, y1, z1) = box
    return {"down": y0 <= 0, "up": y1 >= 16, "north": z0 <= 0,
            "south": z1 >= 16, "west": x0 <= 0, "east": x1 >= 16}[face]


def element(box, texture, tint=None):
    """One box, textured on all six faces. UVs are left implicit so Minecraft derives them from the
    box's position -- which is what makes the texture read as carved out of a whole block rather
    than stretched across the shape."""
    faces = {}
    for face in FACES:
        entry = {"texture": texture}
        if tint is not None:
            entry["tintindex"] = tint
        if cullface(face, box):
            entry["cullface"] = face
        faces[face] = entry
    return {"from": list(box[0]), "to": list(box[1]), "faces": faces}


def tinted(boxes, layers, textures, offset=0):
    """The ten stone layers, coincident on the same boxes. The offset shifts a double slab's upper
    half onto tint indices 10-19, which is what lets CompositeBlockColour resolve each half from its
    own composition."""
    out = []
    for tint, texture in enumerate(layers):
        key = f"layer{offset + tint}"
        textures[key] = f"granularity:block/{texture}"
        for box in boxes:
            out.append(element(box, f"#{key}", offset + tint))
    return out


def write(rel, obj):
    with open(os.path.join(ASSETS, rel), "w") as fh:
        json.dump(obj, fh, indent=2)


def write_model(name, elements, textures):
    write(f"models/block/{name}.json",
          {"parent": "minecraft:block/block",
           "render_type": "minecraft:cutout_mipped",
           "textures": textures, "elements": elements})
    return len(elements)


def shape_model(name, boxes, layers, particle):
    textures = {"particle": f"granularity:block/{particle}"}
    return write_model(name, tinted(boxes, layers, textures), textures)


def double_model(name, layers, particle, halves=None):
    """The double slab: two independent half-blocks in one model.

    A double slab is two slabs, not one block, so its halves may differ in stone -- and in what grows
    on them. The far half's tint indices start at UPPER_BASE, which is also how OverlayBakedModel
    tells the two halves apart when it copies quads.

    `halves` is the pair of boxes to split into, so the same function serves a slab standing on end:
    the concept is "two halves along an axis", and which axis never mattered to any of this.
    """
    near, far = halves or (BOTTOM, TOP)
    textures = {"particle": f"granularity:block/{particle}"}
    elements = tinted([near], layers, textures) + tinted([far], layers, textures, UPPER_BASE)
    return write_model(name, elements, textures)


# The six halves a slab can occupy, by axis. TYPE=BOTTOM is always the negative side of the axis and
# TOP the positive one, so "bottom" and "top" keep meaning exactly what they meant on y.
#
# Vertical slabs cost this mod one blockstate property and nine models. Vanilla has sixty slab blocks
# and would pay for orientation sixty times over; we have one, because what a slab is made of and how
# it is worked are components rather than blocks. See CompositeSlabBlock.
SLAB_HALVES = {
    "x": (((0, 0, 0), (8, 16, 16)), ((8, 0, 0), (16, 16, 16))),
    "y": (BOTTOM, TOP),
    "z": (((0, 0, 0), (16, 16, 8)), ((0, 0, 8), (16, 16, 16))),
}


# Vanilla's lever, element for element. The base is the part made of stone and so the part that
# gets layered; the switch is oak and stays exactly as vanilla draws it, rotation and all.
LEVER_BASE = {
    "from": [5, -0.02, 4], "to": [11, 2.98, 12],
    "faces": {
        "down":  {"uv": [5, 4, 11, 12], "cullface": "down"},
        "up":    {"uv": [5, 4, 11, 12]},
        "north": {"uv": [5, 0, 11, 3]},
        "south": {"uv": [5, 0, 11, 3]},
        "west":  {"uv": [4, 0, 12, 3]},
        "east":  {"uv": [4, 0, 12, 3]},
    },
}

LEVER_SWITCH_FACES = {
    "up":    {"uv": [7, 6, 9, 8], "texture": "#lever"},
    "north": {"uv": [7, 6, 9, 16], "texture": "#lever"},
    "south": {"uv": [7, 6, 9, 16], "texture": "#lever"},
    "west":  {"uv": [7, 6, 9, 16], "texture": "#lever"},
    "east":  {"uv": [7, 6, 9, 16], "texture": "#lever"},
}


def lever_model(name, layers, angle):
    """A lever whose stone base is ten coincident layers.

    Explicit UVs, unlike the shapes above: vanilla's lever samples specific parts of the cobblestone
    sprite, and our layer sprites share that sprite's layout, so copying its UVs verbatim keeps every
    layer registered with the stone it came from.

    Tint index 0 is present on the base, which is also what lets an overlay find the lever's surface
    without knowing anything about levers.
    """
    textures = {"particle": "granularity:block/cobblestone_full", "lever": "minecraft:block/lever"}
    elements = []
    for tint, texture in enumerate(layers):
        key = f"layer{tint}"
        textures[key] = f"granularity:block/{texture}"
        faces = {}
        for face, spec in LEVER_BASE["faces"].items():
            faces[face] = dict(spec, texture=f"#{key}", tintindex=tint)
        elements.append({"from": LEVER_BASE["from"], "to": LEVER_BASE["to"], "faces": faces})
    elements.append({
        "from": [7, 1, 7], "to": [9, 11, 9],
        "rotation": {"origin": [8, 1, 8], "axis": "x", "angle": angle},
        "faces": {face: dict(spec) for face, spec in LEVER_SWITCH_FACES.items()},
    })
    write(f"models/block/{name}.json",
          {"ambientocclusion": False, "render_type": "minecraft:cutout_mipped",
           "textures": textures, "elements": elements})
    return len(elements)


def lever_blockstate():
    """Vanilla's own 24 variants, pointed at our models.

    `powered=false` draws the *on* model because vanilla names them by lever position rather than by
    signal, and matching that keeps our lever indistinguishable from theirs in a redstone build.
    """
    variants = {}
    faces = {
        "ceiling": {"x": 180, "north": 180, "south": 0, "east": 270, "west": 90},
        "floor":   {"x": 0, "north": 0, "south": 180, "east": 90, "west": 270},
        "wall":    {"x": 90, "north": 0, "south": 180, "east": 90, "west": 270},
    }
    for face, turns in faces.items():
        for facing in ("north", "south", "east", "west"):
            for powered in (False, True):
                v = {"model": "granularity:block/lever" if powered else "granularity:block/lever_on"}
                if turns["x"]:
                    v["x"] = turns["x"]
                if turns[facing]:
                    v["y"] = turns[facing]
                variants[f"face={face},facing={facing},powered={str(powered).lower()}"] = v
    write("blockstates/lever.json", {"variants": variants})
    return len(variants)


# --- Stoneware: vanilla blocks rebuilt on grains --------------------------------------------------
#
# These differ from cobblestone in two ways, and one function below absorbs both. Their faces do not
# share a sprite -- top, side and front are three different textures -- and not every face is stone:
# a furnace has a mouth, an observer a slot, a piston a plate. So a face declares which it is, and a
# machined face is simply left out of the layering and drawn once, untinted, from a pre-darkened
# sprite (see extract_stoneware.py). Tinting one would colour a furnace's fire or a piston's plate.
#
# The geometry below is vanilla's, resolved through its parent chain by hand, so a Granularity
# dispenser sits flush against a vanilla one and samples the same pixels.


def stone(family, **rest):
    """A face made of stone: layered, dimpled, and tinted by the grains."""
    return dict(family=family, **rest)


def machined(texture, **rest):
    """A face that is not stone and takes no colour from it. Drawn once, untinted.

    Only the furnace mouth, and only because a tint on that face would reach the fire behind it. The
    fire is the one thing on these blocks that must look the same on every one of them.

    It still carries a tint index -- PLAIN_TINT, which the colour handler answers white, so the face
    draws exactly as the sprite was made. The index is there so the face is *addressable*: overlays
    find a block's surface by tint index, and a face with no index at all is invisible to them. Moss
    should be able to grow over a furnace door.
    """
    return dict(texture=texture, tinted=PLAIN_TINT, **rest)


def wood(texture, **rest):
    """A face made of the timber the block was built from, not of stone.

    Takes {@code WOOD_TINT}, which {@code CompositeBlockColour} resolves from the planks recorded on
    the block rather than from its grains — so a piston built with spruce has a spruce plate however
    dark the stone around it is. The sprite is greyscale for the same reason every other tinted
    sprite is.
    """
    return dict(texture=texture, tinted=WOOD_TINT, **rest)


def metal(texture, **rest):
    """A face made of the metal the block was built from: a piston's brackets and braces.

    Takes {@code METAL_TINT}, resolved from the ingot recorded on the block. The third material on a
    piston, after its stone and its timber, and the reason a face can need three coincident copies.
    """
    return dict(texture=texture, tinted=METAL_TINT, **rest)


def kind_face(kind, texture, **rest):
    """`wood(...)` or `metal(...)`, chosen by name so the head can be built once per material."""
    return (wood if kind == "wood" else metal)(texture, **rest)


def second_stone(texture, **rest):
    """A face made of the block's *other* stone, where a block is built from two.

    Takes {@code UPPER_BASE}, the index a double slab's upper half already uses — the renderer
    resolves it against the block entity's second composition and falls back to the first when there
    is none. Reusing it is deliberate: "one block, two stones" is a concept this codebase already
    has, and a stonecutter is the same shape of thing as a double slab made of two rocks.
    """
    return dict(texture=texture, tinted=UPPER_BASE, **rest)


def worked(texture, **rest):
    """A face that is not stone but is the same colour as it: a muzzle, a plate, a slot.

    Takes tintindex 0, the block's averaged colour, so machinery reads as cut from the same rock as
    the block around it. The sprite is greyscaled at extraction precisely so that this tint is the
    only colour in the result.
    """
    return dict(texture=texture, tinted=True, **rest)


def stoneware_layer(elements, textures, layer):
    """One coincident copy of the geometry.

    Layer 0 is the whole block: stone faces in grey at tintindex 0 for the averaged tint, machined
    faces at their own sprite with no tintindex. Layers 1..N carry *only* the stone faces, each
    showing that layer's dimple region in one grain's exact colour -- which is what keeps a machined
    face appearing exactly once no matter how many grains the block costs.
    """
    out = []
    for source in elements:
        faces = {}
        for direction, spec in source["faces"].items():
            family = spec.get("family")
            if family is None:
                if layer > 0:
                    continue
                key = spec["texture"].rsplit("/", 1)[-1]
                textures[key] = spec["texture"]
                face = {"texture": f"#{key}"}
                # True means the block's own averaged colour; an int names a different tint
                # entirely, which is how a wooden plate escapes the stone's palette.
                tint = spec.get("tinted")
                if tint:
                    face["tintindex"] = 0 if tint is True else tint
            else:
                suffix = f"shape_{layer}" if layer else "base"
                key = f"{family}_{suffix}"
                textures[key] = f"granularity:block/{key}"
                face = {"texture": f"#{key}", "tintindex": layer}
            for extra in ("uv", "rotation", "cullface"):
                if spec.get(extra) is not None:
                    face[extra] = spec[extra]
            faces[direction] = face
        # An element with nothing but machined faces contributes only to layer 0.
        if faces:
            out.append({"from": list(source["from"]), "to": list(source["to"]), "faces": faces})
    return out


def stoneware_model(name, elements, grains, particle):
    textures = {"particle": f"granularity:block/{particle}"}
    out = []
    for layer in range(grains + 1):
        out.extend(stoneware_layer(elements, textures, layer))
    return write_model(name, out, textures)


def orientable(top, side, front):
    """Vanilla's block/orientable: a cube with one worked face, used by the furnace and dispenser.

    `front` decides for itself whether it is tinted, which is the whole difference between a furnace
    and a dispenser here: same geometry, same sprites underneath, but a mouth with fire behind it
    cannot take a colour and a muzzle should.
    """
    return [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
        "down":  stone(top, cullface="down"),
        "up":    stone(top, cullface="up"),
        "north": dict(front, cullface="north"),
        "south": stone(side, cullface="south"),
        "west":  stone(side, cullface="west"),
        "east":  stone(side, cullface="east")}}]


def orientable_vertical(side, front):
    """Vanilla's block/orientable_vertical, worked face up.

    Note that a vertical dispenser's `side` is `furnace_top`, not `furnace_side` -- vanilla shows the
    top sprite on all five of its other faces, and matching that is why this takes one family rather
    than two.
    """
    return [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
        "down":  stone(side, cullface="down"),
        "up":    dict(front, cullface="up"),
        "north": stone(side, cullface="north"),
        "south": stone(side, cullface="south"),
        "west":  stone(side, cullface="west"),
        "east":  stone(side, cullface="east")}}]


def observer_elements(back):
    """Vanilla's block/observer, which writes its own UVs -- note the flipped V on the up face.

    The back gets a second, coincident element carrying nothing but the indicator lamp. The lamp is
    cut out of the tinted sprite at extraction and drawn here untinted, so a slate observer has a
    slate back and a red light rather than a slate-coloured one. Same trick as the dimple layers,
    for the same reason: one face, two things on it that want different colours.
    """
    faces = {
        "down":  stone("observer_top", uv=[0, 0, 16, 16], cullface="down"),
        "up":    stone("observer_top", uv=[0, 16, 16, 0], cullface="up"),
        "north": worked("granularity:block/observer_front_tint", uv=[0, 0, 16, 16],
                        cullface="north"),
        "south": worked(f"granularity:block/{back}_tint", uv=[0, 0, 16, 16], cullface="south"),
        "west":  stone("observer_side", uv=[0, 0, 16, 16], cullface="west"),
        "east":  stone("observer_side", uv=[0, 0, 16, 16], cullface="east"),
    }
    return [
        {"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces},
        {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
            "south": machined(f"granularity:block/{back}_lit", uv=[0, 0, 16, 16],
                              cullface="south")}},
    ]


# Vanilla's block/template_piston, taken apart along the seam vanilla hides.
#
# Vanilla draws the retracted piston as one cube wearing the whole side sprite. But that sprite is
# not one material: its top four rows are the *head's* band -- `template_piston_head` samples exactly
# uv [0,0,16,4] for them -- and rows 4-16 are the body, which is what `piston_extended` samples once
# the head has left. Retracted, the two sit flush and vanilla has no reason to tell them apart.
#
# We do, because they are timber and stone. Splitting the cube at z=4 and giving each piece the rows
# it owns puts every pixel exactly where vanilla puts it -- the mapping is linear, so z=0-4 is v=0-4
# either way -- while letting the band take the wood tint and the body keep its grains.
#
# Alpha-cutting the sprite instead would not work: the head's band reads those same four rows out of
# the same sprite, so blanking them would blank the extended arm too.
PISTON = [
    {"from": [0, 0, 4], "to": [16, 16, 16], "faces": {
        "down":  stone("piston_side", uv=[0, 4, 16, 16], rotation=180, cullface="down"),
        "up":    stone("piston_side", uv=[0, 4, 16, 16], cullface="up"),
        "south": stone("piston_back", uv=[0, 0, 16, 16], cullface="south"),
        "west":  stone("piston_side", uv=[0, 4, 16, 16], rotation=270, cullface="west"),
        "east":  stone("piston_side", uv=[0, 4, 16, 16], rotation=90, cullface="east")}},
    # The head at rest. The same four-deep block that travels when it extends, drawn from the same
    # sprites in the same two materials -- retracting one should not change what it is made of.
    *[{"from": [0, 0, 0], "to": [16, 16, 4], "faces": {
        "down":  kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4], rotation=180,
                           cullface="down"),
        "up":    kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4],
                           cullface="up"),
        "north": kind_face(k, f"granularity:block/piston_top_{k}", uv=[0, 0, 16, 16],
                           cullface="north"),
        "west":  kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4], rotation=270,
                           cullface="west"),
        "east":  kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4], rotation=90,
                           cullface="east")}} for k in ("wood", "metal")],
]

# Vanilla's block/piston_extended: the body is only twelve deep once the head has left it, and the
# side faces sample the matching twelve rows so the banding does not slide. The inner face is at
# z=4 rather than on the boundary, so it takes no cullface.
PISTON_EXTENDED = [{"from": [0, 0, 4], "to": [16, 16, 16], "faces": {
    "down":  stone("piston_side", uv=[0, 4, 16, 16], rotation=180, cullface="down"),
    "up":    stone("piston_side", uv=[0, 4, 16, 16], cullface="up"),
    "north": worked("granularity:block/piston_inner_tint", uv=[0, 0, 16, 16]),
    "south": stone("piston_back", uv=[0, 0, 16, 16], cullface="south"),
    "west":  stone("piston_side", uv=[0, 4, 16, 16], rotation=270, cullface="west"),
    "east":  stone("piston_side", uv=[0, 4, 16, 16], rotation=90, cullface="east")}}]

def piston_head_elements(arm_to, arm_uv, arm_west_uv):
    """Vanilla's block/template_piston_head: the plate, plus the arm that reaches back to the body.

    Timber all the way through, unlike the piston it comes out of. The head is the part that moves,
    and it is one piece of wood: plate, the band around its edge, and the arm all take `WOOD_TINT`,
    so a spruce piston pushes out a spruce ram rather than a spruce plate on a stone rod.

    That leaves no stone on this model at all, which is why it is built with no grain layers. Nothing
    here is drawn from the composition, so there are no dimples to split -- see the `grains=0` at the
    call site. The sprites are the stone family's greyscale bases, used purely as shading.
    """
    def plate_faces(kind):
        sprite = f"granularity:block/piston_top_{kind}"
        return {"north": kind_face(kind, sprite, uv=[0, 0, 16, 16], cullface="north"),
                # No cullface: this face is at z=4, inside the block, against the arm.
                "south": kind_face(kind, sprite, uv=[0, 0, 16, 16])}

    def band_faces(kind):
        sprite = f"granularity:block/piston_side_{kind}"
        return {"down": kind_face(kind, sprite, uv=[0, 0, 16, 4], rotation=180, cullface="down"),
                "up":   kind_face(kind, sprite, uv=[0, 0, 16, 4], cullface="up"),
                "west": kind_face(kind, sprite, uv=[0, 0, 16, 4], rotation=270, cullface="west"),
                "east": kind_face(kind, sprite, uv=[0, 0, 16, 4], rotation=90, cullface="east")}

    def arm_faces(kind):
        sprite = f"granularity:block/piston_side_{kind}"
        return {"down": kind_face(kind, sprite, uv=arm_uv, rotation=90),
                "up":   kind_face(kind, sprite, uv=arm_uv, rotation=270),
                "west": kind_face(kind, sprite, uv=arm_west_uv),
                "east": kind_face(kind, sprite, uv=arm_uv)}

    # Two coincident copies of each piece, one per material. They cannot share an element, because
    # an element carries at most one face per direction -- the same reason the dimple layers are
    # separate elements.
    out = []
    for kind in ("wood", "metal"):
        out.append({"from": [0, 0, 0], "to": [16, 16, 4],
                    "faces": dict(plate_faces(kind), **band_faces(kind))})
        out.append({"from": [6, 6, 4], "to": [10, 10, arm_to], "faces": arm_faces(kind)})
    return out


PISTON_HEAD = piston_head_elements(20, [0, 0, 16, 4], [16, 4, 0, 0])
PISTON_HEAD_SHORT = piston_head_elements(16, [4, 0, 16, 4], [16, 4, 4, 0])


def piston_head_blockstate():
    """Vanilla's 24, pointed at our two models.

    `type=sticky` never occurs — a Granularity piston is never sticky — but every state a block can
    reach needs a variant or the model loader reports it missing, so the sticky half maps to the same
    models rather than being left out.
    """
    variants = {}
    for facing, turn in TURN_VERTICAL.items():
        for short in (False, True):
            for kind in ("normal", "sticky"):
                variants[f"facing={facing},short={str(short).lower()},type={kind}"] = variant(
                        "piston_head_short" if short else "piston_head", turn)
    write("blockstates/piston_head.json", {"variants": variants})
    return len(variants)


# Vanilla's block/piston_inventory -- a plain cube, because the item is never extended.
PISTON_INVENTORY = [
    {"from": [0, 0, 0], "to": [16, 12, 16], "faces": {
        "down":  stone("piston_back", cullface="down"),
        "north": stone("piston_side", uv=[0, 4, 16, 16], cullface="north"),
        "south": stone("piston_side", uv=[0, 4, 16, 16], cullface="south"),
        "west":  stone("piston_side", uv=[0, 4, 16, 16], cullface="west"),
        "east":  stone("piston_side", uv=[0, 4, 16, 16], cullface="east")}},
    *[{"from": [0, 12, 0], "to": [16, 16, 16], "faces": {
        "up":    kind_face(k, f"granularity:block/piston_top_{k}", cullface="up"),
        "north": kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4],
                           cullface="north"),
        "south": kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4],
                           cullface="south"),
        "west":  kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4],
                           cullface="west"),
        "east":  kind_face(k, f"granularity:block/piston_side_{k}", uv=[0, 0, 16, 4],
                           cullface="east")}} for k in ("wood", "metal")],
]

# Vanilla's block/stonecutter, element for element -- a 16x9x16 bench with a flat saw plane standing
# on top of it.
#
# The odd one out in this family, and deliberately so. Every other stoneware block is built from
# chunks and shows one dimple per chunk; a stonecutter is built from *smooth* stone, and smooth is
# precisely the finish that has stopped showing its grains. So its body is one averaged colour over
# every face -- `worked`, tintindex 0 -- and it carries zero grain layers.
#
# It is **four** materials, which is what makes it worth building at all -- and three of them are
# chosen by the player, one per slot of the recipe:
#
#     row " B "   the bar        -> METAL_TINT, the saw blade
#     row "#L#"   the left rock  -> tintindex 0, everything below the wooden rail
#                 the log        -> WOOD_TINT, the frame: legs, rail and corner brackets
#                 the right rock -> UPPER_BASE, everything above the rail, and the working top
#
# The two stones are the point. Vanilla's own side texture is a wooden frame with a **full-width rail
# across rows 9 and 10**, and stone above it and below it -- so the block is already drawn as two
# stone sections with timber between them, and nothing but the tint had to be invented to let a
# player build it out of two rocks. `UPPER_BASE` is the index a double slab's upper half already
# uses; "one block, two stones" is a concept this codebase had before this block existed.
#
# Geometry is **not** split to achieve this. All three copies below are the same 16x9x16 box, and each
# draws through an alpha mask that keeps only its own pixels -- the same layering `stoneware_layer`
# does for dimples. Splitting the box would not have worked anyway: the wooden legs sit at the same
# height as the stone beside them, so no cut plane separates them.
#
# The saw points at vanilla's texture rather than a copy of it: `stonecutter_saw` is already pure
# greyscale, so it is already a tintable sprite, and pointing at it avoids re-extracting a 16x48
# three-frame animation and its .mcmeta. See extract_stoneware.py.
SAW = "minecraft:block/stonecutter_saw"
BENCH = ((0, 0, 0), (16, 9, 16))
# Vanilla's own UVs: the bench is nine tall and samples the bottom nine rows of a side sprite, one
# texture row per world unit.
SIDE_UV = [0, 7, 16, 16]
FACE_UV = [0, 0, 16, 16]


def bench_sides(face, texture, **rest):
    """The four sides of the bench, all sampling the same sprite, each culled against its neighbour."""
    return {side: face(texture, uv=SIDE_UV, cullface=side, **rest)
            for side in ("north", "south", "west", "east")}


STONECUTTER = [
    # The lower stone: the body below the rail, and the underside.
    {"from": list(BENCH[0]), "to": list(BENCH[1]), "faces": dict(
        bench_sides(worked, "granularity:block/stonecutter_side_lower"),
        down=worked("granularity:block/stonecutter_bottom_tint", uv=FACE_UV, cullface="down"))},
    # The upper stone: the strip above the rail, and the working surface on top. No cullface up --
    # the bench is nine deep, so nothing is ever flush above it.
    {"from": list(BENCH[0]), "to": list(BENCH[1]), "faces": dict(
        bench_sides(second_stone, "granularity:block/stonecutter_side_upper"),
        up=second_stone("granularity:block/stonecutter_top_stone", uv=FACE_UV))},
    # The timber: the legs and the rail that divides the two stones, and the four corner brackets.
    {"from": list(BENCH[0]), "to": list(BENCH[1]), "faces": dict(
        bench_sides(wood, "granularity:block/stonecutter_side_wood"),
        up=wood("granularity:block/stonecutter_top_wood", uv=FACE_UV))},
    # A zero-thickness plane at z=8, drawn from both sides -- the south face's u runs backwards so the
    # blade is not mirrored when seen from behind. The v range 9..16 is within one animation frame.
    {"from": [1, 9, 8], "to": [15, 16, 8], "faces": {
        "north": metal(SAW, uv=[1, 9, 15, 16]),
        "south": metal(SAW, uv=[15, 9, 1, 16])}},
]


def stonecutter_blockstate():
    variants = {f"facing={facing}": variant("stonecutter", turn) for facing, turn in TURN.items()}
    write("blockstates/stonecutter.json", {"variants": variants})
    return len(variants)


# Vanilla's y rotations for a horizontally-facing block, plus the x rotations for the two that can
# also point along the vertical.
TURN = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}}
TURN_VERTICAL = dict(TURN, up={"x": 270}, down={"x": 90})


def variant(model, turn):
    return dict({"model": f"granularity:block/{model}"}, **turn)


def furnace_blockstate():
    variants = {}
    for facing, turn in TURN.items():
        for lit in (False, True):
            variants[f"facing={facing},lit={str(lit).lower()}"] = variant(
                    "furnace_on" if lit else "furnace", turn)
    write("blockstates/furnace.json", {"variants": variants})
    return len(variants)


def dispenser_blockstate(name):
    """Only `facing` appears: `triggered` picks no model, exactly as vanilla's own file has it."""
    variants = {f"facing={facing}": variant(name, turn) for facing, turn in TURN.items()}
    variants["facing=up"] = variant(f"{name}_vertical", {})
    variants["facing=down"] = variant(f"{name}_vertical", {"x": 180})
    write(f"blockstates/{name}.json", {"variants": variants})
    return len(variants)


def observer_blockstate():
    variants = {}
    for facing, turn in TURN_VERTICAL.items():
        for powered in (False, True):
            variants[f"facing={facing},powered={str(powered).lower()}"] = variant(
                    "observer_on" if powered else "observer", turn)
    write("blockstates/observer.json", {"variants": variants})
    return len(variants)


def piston_blockstate():
    variants = {}
    for facing, turn in TURN_VERTICAL.items():
        for extended in (False, True):
            variants[f"extended={str(extended).lower()},facing={facing}"] = variant(
                    "piston_base" if extended else "piston", turn)
    write("blockstates/piston.json", {"variants": variants})
    return len(variants)


def main():
    cobble = ["cobblestone_base"] + [f"cobblestone_shape_{i}" for i in range(1, 10)]
    total = 0

    for shape in BOXES:
        total += shape_model(f"cobblestone_{shape}", BOXES[shape], cobble, "cobblestone_full")
    total += shape_model("cobblestone_layered", [FULL], cobble, "cobblestone_full")
    for axis, (near, far) in SLAB_HALVES.items():
        # y keeps the original names, so the item model and every existing reference stay put.
        lo = "bottom" if axis == "y" else f"{axis}_bottom"
        hi = "top" if axis == "y" else f"{axis}_top"
        both = "double" if axis == "y" else f"{axis}_double"
        total += shape_model(f"cobblestone_slab_layered_{lo}", [near], cobble, "cobblestone_full")
        total += shape_model(f"cobblestone_slab_layered_{hi}", [far], cobble, "cobblestone_full")
        total += double_model(f"cobblestone_slab_layered_{both}", cobble, "cobblestone_full",
                              (near, far))
    print(f"cobblestone: {total} elements across 11 models")

    write("blockstates/cobblestone.json",
          {"variants": {"": {"model": "granularity:block/cobblestone_layered"}}})
    slab_variants = {}
    for axis in SLAB_HALVES:
        for kind, suffix in (("bottom", "bottom"), ("top", "top"), ("double", "double")):
            model = suffix if axis == "y" else f"{axis}_{suffix}"
            slab_variants[f"axis={axis},type={kind}"] = {
                "model": f"granularity:block/cobblestone_slab_layered_{model}"}
    write("blockstates/cobblestone_slab.json", {"variants": slab_variants})
    print(f"  slab: {len(slab_variants)} variants across {len(SLAB_HALVES)} axes")

    # --- Stairs: 40 variants over 3 models, all rotation done by the blockstate ---
    variants = {}
    turn = {"east": 0, "south": 90, "west": 180, "north": 270}
    for half in ("bottom", "top"):
        for facing, y in turn.items():
            for shape, model, extra in (
                    ("straight", "stairs", 0),
                    ("inner_left", "stairs_inner", -90),
                    ("inner_right", "stairs_inner", 0),
                    ("outer_left", "stairs_outer", -90),
                    ("outer_right", "stairs_outer", 0)):
                v = {"model": f"granularity:block/cobblestone_{model}"}
                rot = (y + extra) % 360
                # A top stair is the bottom one flipped, which reverses corner handedness.
                if half == "top":
                    v["x"] = 180
                    if shape.startswith("inner") or shape.startswith("outer"):
                        rot = (rot + 90) % 360
                if rot:
                    v["y"] = rot
                if rot or half == "top":
                    v["uvlock"] = True
                variants[f"facing={facing},half={half},shape={shape}"] = v
    write("blockstates/cobblestone_stairs.json", {"variants": variants})

    # --- Wall: multipart, which the old post-bake composition could not have done at all ---
    multipart = [{"when": {"up": "true"},
                  "apply": {"model": "granularity:block/cobblestone_wall_post"}}]
    for side, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for height, model in (("low", "wall_side"), ("tall", "wall_side_tall")):
            apply = {"model": f"granularity:block/cobblestone_{model}", "uvlock": True}
            if y:
                apply["y"] = y
            multipart.append({"when": {side: height}, "apply": apply})
    write("blockstates/cobblestone_wall.json", {"multipart": multipart})
    print(f"  {len(variants)} stair variants, {len(multipart)} wall multipart entries")

    write("models/item/cobblestone.json", {"parent": "granularity:block/cobblestone_layered"})
    write("models/item/cobblestone_slab.json",
          {"parent": "granularity:block/cobblestone_slab_layered_bottom"})
    write("models/item/cobblestone_stairs.json", {"parent": "granularity:block/cobblestone_stairs"})
    write("models/item/cobblestone_wall.json",
          {"parent": "granularity:block/cobblestone_wall_inventory"})

    lever_model("lever", cobble, -45)
    lever_model("lever_on", cobble, 45)
    print(f"  lever: {lever_blockstate()} variants over 2 models")
    # Vanilla's lever item is the switch alone with no stone in it, so there is nothing to tint --
    # a slate lever and a granite one are identical in the hand and differ only once placed.
    write("models/item/lever.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:block/lever"}})

    # --- Stoneware. The grain count is what the recipe costs, so each block splits its dimpling a
    # different number of ways -- a dispenser and a furnace share furnace_side and still differ.
    for name, front, grains in (("furnace", "furnace_front_dark", 8),
                                ("furnace_on", "furnace_front_on_dark", 8)):
        layers = stoneware_model(name, orientable("furnace_top", "furnace_side",
                                                  machined(f"granularity:block/{front}")),
                                 grains, "furnace_side_base")
    print(f"  furnace: {furnace_blockstate()} variants, {layers} layers over 2 models")
    write("models/item/furnace.json", {"parent": "granularity:block/furnace"})

    for name, grains in (("dispenser", 7), ("dropper", 7)):
        stoneware_model(name, orientable(f"{name}_top", f"{name}_side",
                                         worked(f"granularity:block/{name}_front_tint")),
                        grains, f"{name}_side_base")
        # Pointed up or down a dispenser shows the *top* sprite on all five stone faces, so its
        # vertical form is layered from that family alone.
        stoneware_model(f"{name}_vertical",
                        orientable_vertical(f"{name}_top",
                                            worked(f"granularity:block/{name}_front_vertical_tint")),
                        grains, f"{name}_top_base")
        print(f"  {name}: {dispenser_blockstate(name)} variants, {grains} grains over 2 models")
        write(f"models/item/{name}.json", {"parent": f"granularity:block/{name}"})

    stoneware_model("observer", observer_elements("observer_back"),
                    6, "observer_side_base")
    stoneware_model("observer_on", observer_elements("observer_back_on"),
                    6, "observer_side_base")
    print(f"  observer: {observer_blockstate()} variants, 6 grains over 2 models")
    write("models/item/observer.json", {"parent": "granularity:block/observer"})

    stoneware_model("piston", PISTON, 4, "piston_side_base")
    stoneware_model("piston_base", PISTON_EXTENDED, 4, "piston_side_base")
    stoneware_model("piston_inventory", PISTON_INVENTORY, 4, "piston_side_base")
    # Zero grains: the head is timber, and nothing on it is drawn from the composition.
    stoneware_model("piston_head", PISTON_HEAD, 0, "piston_side_base")
    stoneware_model("piston_head_short", PISTON_HEAD_SHORT, 0, "piston_side_base")
    print(f"  piston: {piston_blockstate()} variants, 4 grains over 3 models")
    print(f"  piston head: {piston_head_blockstate()} variants over 2 models")
    write("models/item/piston.json", {"parent": "granularity:block/piston_inventory"})

    # Zero grains: a stonecutter is built from smooth stone, which is the finish that has stopped
    # showing its grains, so there is nothing to dimple. Its colours come from four indices instead —
    # tint 0 and UPPER_BASE for its two stones, WOOD_TINT for the frame, METAL_TINT for the blade.
    stoneware_model("stonecutter", STONECUTTER, 0, "stonecutter_bottom_tint")
    print(f"  stonecutter: {stonecutter_blockstate()} variants, 0 grains over 1 model")
    write("models/item/stonecutter.json", {"parent": "granularity:block/stonecutter"})

    shape_model("gravel_layered", [FULL], ["gravel_base"] + [f"gravel_shape_{i}" for i in range(1, 10)],
                "gravel_full")
    write("blockstates/gravel.json", {"variants": {"": {"model": "granularity:block/gravel_layered"}}})
    write("models/item/gravel.json", {"parent": "granularity:block/gravel_layered"})


if __name__ == "__main__":
    main()
